# Voucher UI Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the list+dialog voucher UI with a single inline VoucherPanel, remove the DRAFT/BOOKED distinction and hash chain, add bidirectional account lookup with balance columns, and move company/fiscal year selectors to the bottom bar.

**Architecture:** The voucher tab becomes a single `VoucherPanel` with header fields, navigation toolbar, and tabbed lines/attachments/history. VoucherService is simplified to remove the booking/hash flow — vouchers are always `ACTIVE` and editable until their period is locked. A new `AccountService.calculateAccountBalance()` method powers the calculated balance columns. The MainFrame bottom bar becomes the global context for company and fiscal year.

**Tech Stack:** Groovy, Swing, H2, JUnit 5, Gradle

---

## File Structure

### New files
- `app/src/main/resources/db/migrations/V17__voucher_lifecycle_simplification.sql` — migration: DRAFT→ACTIVE, BOOKED→ACTIVE, drop hash columns/table
- `app/src/main/groovy/se/alipsa/accounting/ui/VoucherPanel.groovy` — new inline voucher editor panel
- `app/src/main/groovy/se/alipsa/accounting/ui/AccountLookupPopup.groovy` — popup for account name search
- `app/src/test/groovy/unit/se/alipsa/accounting/service/AccountBalanceTest.groovy` — tests for calculateAccountBalance
- `app/src/test/groovy/integration/se/alipsa/accounting/service/VoucherLifecycleMigrationTest.groovy` — tests for V17 migration

### Modified files
- `app/src/main/groovy/se/alipsa/accounting/domain/VoucherStatus.groovy` — replace DRAFT/BOOKED with ACTIVE
- `app/src/main/groovy/se/alipsa/accounting/domain/Voucher.groovy` — remove hash fields
- `app/src/main/groovy/se/alipsa/accounting/service/VoucherService.groovy` — remove hash chain, simplify save/update flow
- `app/src/main/groovy/se/alipsa/accounting/service/AccountService.groovy` — add calculateAccountBalance()
- `app/src/main/groovy/se/alipsa/accounting/service/AuditLogService.groovy` — rename recordVoucherBooked→recordVoucherSaved
- `app/src/main/groovy/se/alipsa/accounting/ui/MainFrame.groovy` — move company to bottom bar, add fiscal year, replace VoucherListPanel with VoucherPanel
- `app/src/main/groovy/se/alipsa/accounting/ui/ActiveCompanyManager.groovy` — add fiscal year tracking
- `app/src/main/resources/i18n/messages.properties` — new/updated i18n keys
- `app/src/main/resources/i18n/messages_sv.properties` — new/updated i18n keys
- All services with SQL `v.status in ('BOOKED', 'CORRECTION')` — change to `('ACTIVE', 'CORRECTION')`

### Files to delete (after VoucherPanel is complete)
- `app/src/main/groovy/se/alipsa/accounting/ui/VoucherListPanel.groovy`
- `app/src/main/groovy/se/alipsa/accounting/ui/VoucherEditor.groovy`

---

## Task 1: Database Migration — Voucher Lifecycle Simplification

**Files:**
- Create: `app/src/main/resources/db/migrations/V17__voucher_lifecycle_simplification.sql`

- [ ] **Step 1: Write the migration SQL**

```sql
-- V17__voucher_lifecycle_simplification.sql
-- Simplify voucher lifecycle: remove DRAFT/BOOKED distinction, drop hash chain.

-- 1. Convert existing statuses to ACTIVE
update voucher set status = 'ACTIVE' where status = 'DRAFT';
update voucher set status = 'ACTIVE' where status = 'BOOKED';

-- 2. Assign running numbers to former drafts that lack them
-- For each series, allocate numbers sequentially starting after the current max.
update voucher v
   set running_number = (
       select coalesce(max(v2.running_number), 0)
         from voucher v2
        where v2.voucher_series_id = v.voucher_series_id
       ) + (
       select count(*)
         from voucher v3
        where v3.voucher_series_id = v.voucher_series_id
          and v3.running_number is null
          and v3.id <= v.id
       ),
       voucher_number = (
         select s.series_code
           from voucher_series s
          where s.id = v.voucher_series_id
       ) || '-' || (
         select coalesce(max(v2.running_number), 0)
           from voucher v2
          where v2.voucher_series_id = v.voucher_series_id
       ) + (
         select count(*)
           from voucher v3
          where v3.voucher_series_id = v.voucher_series_id
            and v3.running_number is null
            and v3.id <= v.id
       ),
       updated_at = current_timestamp
 where v.running_number is null
   and v.status = 'ACTIVE';

-- 3. Update next_running_number in voucher_series to reflect allocated numbers
update voucher_series vs
   set next_running_number = (
       select coalesce(max(v.running_number), 0) + 1
         from voucher v
        where v.voucher_series_id = vs.id
       ),
       updated_at = current_timestamp;

-- 4. Drop hash-related columns from voucher
alter table voucher drop column previous_hash;
alter table voucher drop column content_hash;
alter table voucher drop column booked_at;

-- 5. Drop the chain head table
drop table voucher_chain_head;

-- 6. Update the status CHECK constraint
-- H2 auto-names CHECK constraints; find and drop the existing status constraint.
-- If the name differs from 'CONSTRAINT_3', query INFORMATION_SCHEMA.TABLE_CONSTRAINTS
-- to find the correct name. The 'if exists' clause makes this safe.
alter table voucher drop constraint if exists constraint_3;
alter table voucher add constraint voucher_status_check
    check (status in ('ACTIVE', 'CANCELLED', 'CORRECTION'));
```

- [ ] **Step 2: Verify migration applies cleanly**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (migration runs during test database bootstrap)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/resources/db/migrations/V17__voucher_lifecycle_simplification.sql
git commit -m "lägg till V17-migrering: förenkla verifikationslivscykeln"
```

---

## Task 2: Update VoucherStatus Enum

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/domain/VoucherStatus.groovy`
- Modify: `app/src/test/groovy/unit/se/alipsa/accounting/domain/ThemeModeTest.groovy` (no change needed, just verify no voucher status tests exist there)

- [ ] **Step 1: Update VoucherStatus enum**

Replace the contents of `app/src/main/groovy/se/alipsa/accounting/domain/VoucherStatus.groovy`:

```groovy
package se.alipsa.accounting.domain

/**
 * Lifecycle states for vouchers.
 */
enum VoucherStatus {
  ACTIVE,
  CANCELLED,
  CORRECTION
}
```

- [ ] **Step 2: Run build to find all compile errors**

Run: `./gradlew compileGroovy 2>&1 | head -60`
Expected: Compilation errors in VoucherService, VoucherListPanel, VoucherEditor, and other files referencing DRAFT or BOOKED. This is expected — we will fix them in subsequent tasks.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/domain/VoucherStatus.groovy
git commit -m "ändra VoucherStatus: ersätt DRAFT/BOOKED med ACTIVE"
```

---

## Task 3: Update Voucher Domain Model

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/domain/Voucher.groovy`

- [ ] **Step 1: Remove hash-related fields from Voucher**

Edit `app/src/main/groovy/se/alipsa/accounting/domain/Voucher.groovy` to remove the three hash/booking fields. The class should become:

```groovy
package se.alipsa.accounting.domain

import groovy.transform.Canonical

import java.time.LocalDate

/**
 * Accounting voucher with editable lines until its period is locked.
 */
@Canonical
final class Voucher {

  Long id
  long fiscalYearId
  long voucherSeriesId
  String seriesCode
  String seriesName
  Integer runningNumber
  String voucherNumber
  LocalDate accountingDate
  String description
  VoucherStatus status
  Long originalVoucherId
  List<VoucherLine> lines = []

  BigDecimal debitTotal() {
    lines.sum(BigDecimal.ZERO) { VoucherLine line -> line.debitAmount ?: BigDecimal.ZERO } as BigDecimal
  }

  BigDecimal creditTotal() {
    lines.sum(BigDecimal.ZERO) { VoucherLine line -> line.creditAmount ?: BigDecimal.ZERO } as BigDecimal
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/domain/Voucher.groovy
git commit -m "ta bort hashkedja och bookedAt från Voucher"
```

---

## Task 4: Simplify VoucherService — Remove Hash Chain and Booking Flow

This is the largest task. VoucherService needs significant changes:
- Remove `bookVoucher()`, `bookDraft()`, `createAndBook()` methods
- Remove hash chain methods (`calculateContentHash`, `lockChainHead`, `updateChainHead`, `ChainHead` class)
- Remove `validateIntegrity()` methods
- Change `insertDraft()` to `saveVoucher()` — assigns running number immediately
- Change `updateDraft()` to `updateVoucher()` — works on ACTIVE vouchers in unlocked periods
- Change `cancelDraft()` to `cancelVoucher()` — works on ACTIVE vouchers in unlocked periods
- Update `createCorrectionVoucher()` to check for ACTIVE status and locked period
- Update all SQL queries: `'BOOKED'` → `'ACTIVE'`, remove hash column references

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/service/VoucherService.groovy`

- [ ] **Step 1: Rewrite VoucherService**

The full rewritten service is too large to include inline. The key changes are:

**Remove these methods entirely:**
- `bookDraft()`
- All `createAndBook()` overloads (4 methods)
- `bookVoucher()` (private)
- `calculateContentHash()` (private)
- `lockChainHead()` (private)
- `updateChainHead()` (private)
- `currentDatabaseTimestamp()` (private)
- `validateIntegrity()` (both public overloads)
- `validateIntegrityForCompany()` (private)
- `ChainHead` inner class
- `PostingPermissions` inner class

**Remove these imports:**
- `java.nio.charset.StandardCharsets`
- `java.security.MessageDigest`
- `java.sql.Timestamp`
- `java.time.LocalDateTime`
- `groovy.transform.PackageScope`

**Rename methods:**
- `insertDraft()` → `insertVoucher()` — now assigns running number and voucher number immediately on insert, inserts with status `'ACTIVE'` instead of `'DRAFT'`
- `updateDraft()` → `updateVoucher()` — change status check from `DRAFT` to `ACTIVE`, add period lock check
- `cancelDraft()` → `cancelVoucher()` — change status check from `DRAFT` to `ACTIVE`, add period lock check
- `createDraft()` → `createVoucher()` — calls `insertVoucher()` instead of `insertDraft()`

**Update `insertVoucher()` (formerly `insertDraft`):**
- Call `allocateRunningNumber()` to get the number
- Compute `voucherNumber = "${seriesCode}-${runningNumber}"`
- Insert with `status = 'ACTIVE'`, `running_number = ?`, `voucher_number = ?`
- Remove the `null, null` for running_number and voucher_number
- Remove `previous_hash`, `content_hash`, `booked_at` from the INSERT
- Add period lock check via `ensurePostingAllowed()`

**Update `updateVoucher()` (formerly `updateDraft`):**
- Change status check: `current.status != VoucherStatus.ACTIVE` → throw
- Change SQL: `and status = 'ACTIVE'`
- Add period lock check via call to `ensurePeriodUnlocked()`
- Error message: `'Verifikationen kan inte ändras — perioden är låst.'`

**Update `cancelVoucher()` (formerly `cancelDraft`):**
- Change status check: `current.status != VoucherStatus.ACTIVE` → throw
- Change SQL: `and status = 'ACTIVE'`
- Add period lock check
- Error message: `'Verifikationen kan inte makuleras — perioden är låst.'`

**Update `createCorrectionVoucher()`:**
- Change: `original.status != VoucherStatus.BOOKED` → `original.status != VoucherStatus.ACTIVE`
- Instead of calling `insertDraft` then `bookVoucher`, call `insertVoucher` with status override for CORRECTION
- Remove hash/chain logic
- Create a new private method `insertCorrectionVoucher()` that inserts directly with `status = 'CORRECTION'`

**Update `ensurePostingAllowed()`:**
- Remove the `targetStatus` parameter
- Remove the `allowReportedVatPeriod` and `allowLockedPeriod` parameters
- Simplify to just check if the period is locked or the VAT period is reported/locked

**Add `ensurePeriodUnlocked()` (new private helper):**
```groovy
private static void ensurePeriodUnlocked(Sql sql, long fiscalYearId, LocalDate accountingDate) {
  GroovyRowResult row = sql.firstRow('''
      select locked
        from accounting_period
       where fiscal_year_id = ?
         and ? between start_date and end_date
  ''', [fiscalYearId, Date.valueOf(accountingDate)])
  if (row != null && Boolean.TRUE == row.get('locked')) {
    throw new IllegalStateException('Perioden är låst och verifikationen kan inte ändras.')
  }
}
```

**Update `mapVoucher()`:**
- Remove `previousHash`, `contentHash`, `bookedAt` from the constructor call
- Remove those column aliases from all SELECT queries throughout the file

**Update `listVouchers()`:**
- Remove `previous_hash`, `content_hash`, `booked_at` from SELECT
- Change `v.status in ('BOOKED', 'CORRECTION')` references if any

**Update `findVoucher()`:**
- Remove `previous_hash`, `content_hash`, `booked_at` from SELECT

- [ ] **Step 2: Run compilation check**

Run: `./gradlew compileGroovy 2>&1 | head -60`
Expected: VoucherService compiles. Errors may remain in VoucherListPanel, VoucherEditor, and other callers — those are addressed in later tasks.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/service/VoucherService.groovy
git commit -m "förenkla VoucherService: ta bort hashkedja och bokföringsflöde"
```

---

## Task 5: Update Dependent Services — Status References

All services that query `v.status in ('BOOKED', 'CORRECTION')` must be updated to use `('ACTIVE', 'CORRECTION')`.

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/service/VatReportSupport.groovy:42`
- Modify: `app/src/main/groovy/se/alipsa/accounting/service/SieImportExportService.groovy:605,730`
- Modify: `app/src/main/groovy/se/alipsa/accounting/service/ClosingService.groovy:431,476`
- Modify: `app/src/main/groovy/se/alipsa/accounting/service/VatService.groovy:360`
- Modify: `app/src/main/groovy/se/alipsa/accounting/service/ReportDataService.groovy:91,515,541,572`
- Modify: `app/src/main/groovy/se/alipsa/accounting/service/AuditLogService.groovy`

- [ ] **Step 1: Replace all `'BOOKED'` SQL references with `'ACTIVE'`**

In each file listed above, find every occurrence of `'BOOKED'` in SQL strings and replace with `'ACTIVE'`. The pattern is always `v.status in ('BOOKED', 'CORRECTION')` → `v.status in ('ACTIVE', 'CORRECTION')`.

Also in `SieImportExportService.groovy`, find any Java/Groovy references to `VoucherStatus.BOOKED` and change to `VoucherStatus.ACTIVE`.

- [ ] **Step 2: Update AuditLogService**

In `AuditLogService.groovy`:
- Rename `recordVoucherBooked` to `recordVoucherSaved`
- Update the event type string from `'VOUCHER_BOOKED'` to `'VOUCHER_SAVED'` (or keep it as-is if it would break audit history — check if audit log entries are read back by type)

- [ ] **Step 3: Update ClosingService references to createAndBook**

`ClosingService` likely calls `voucherService.createAndBook()`. Replace with `voucherService.createVoucher()`. The closing service creates year-end closing vouchers — these should now be created as ACTIVE vouchers directly.

Read `ClosingService.groovy` to find the exact call sites and update them.

- [ ] **Step 4: Update SieImportExportService references**

`SieImportExportService` likely calls `voucherService.createAndBook()` during SIE import. Replace with `voucherService.createVoucher()`.

Read `SieImportExportService.groovy` to find the exact call sites and update them.

- [ ] **Step 5: Update VatService references**

`VatService` likely calls `voucherService.createAndBook()` for VAT transfer bookings. Replace with `voucherService.createVoucher()`.

Read `VatService.groovy` to find the exact call sites and update them.

- [ ] **Step 6: Run compilation check**

Run: `./gradlew compileGroovy 2>&1 | head -60`
Expected: All service classes compile. UI classes may still have errors.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/service/
git commit -m "uppdatera tjänster: ersätt BOOKED med ACTIVE i SQL och metodanrop"
```

---

## Task 6: Add `calculateAccountBalance()` to AccountService

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/service/AccountService.groovy`
- Create: `app/src/test/groovy/unit/se/alipsa/accounting/service/AccountBalanceTest.groovy`

- [ ] **Step 1: Write the test**

Create `app/src/test/groovy/unit/se/alipsa/accounting/service/AccountBalanceTest.groovy`:

```groovy
package unit.se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals

import org.junit.jupiter.api.Test

import se.alipsa.accounting.service.AccountService

class AccountBalanceTest {

  @Test
  void applyDebitToDebitNormalAccount() {
    BigDecimal before = new BigDecimal('1000.00')
    BigDecimal debit = new BigDecimal('500.00')
    BigDecimal credit = BigDecimal.ZERO
    BigDecimal after = AccountService.calculateBalanceAfter(before, debit, credit, 'DEBIT')
    assertEquals(new BigDecimal('1500.00'), after)
  }

  @Test
  void applyCreditToDebitNormalAccount() {
    BigDecimal before = new BigDecimal('1000.00')
    BigDecimal debit = BigDecimal.ZERO
    BigDecimal credit = new BigDecimal('300.00')
    BigDecimal after = AccountService.calculateBalanceAfter(before, debit, credit, 'DEBIT')
    assertEquals(new BigDecimal('700.00'), after)
  }

  @Test
  void applyDebitToCreditNormalAccount() {
    BigDecimal before = new BigDecimal('5000.00')
    BigDecimal debit = new BigDecimal('200.00')
    BigDecimal credit = BigDecimal.ZERO
    BigDecimal after = AccountService.calculateBalanceAfter(before, debit, credit, 'CREDIT')
    assertEquals(new BigDecimal('4800.00'), after)
  }

  @Test
  void applyCreditToCreditNormalAccount() {
    BigDecimal before = new BigDecimal('5000.00')
    BigDecimal debit = BigDecimal.ZERO
    BigDecimal credit = new BigDecimal('1000.00')
    BigDecimal after = AccountService.calculateBalanceAfter(before, debit, credit, 'CREDIT')
    assertEquals(new BigDecimal('6000.00'), after)
  }

  @Test
  void zeroBeforeWithNoTransactions() {
    BigDecimal after = AccountService.calculateBalanceAfter(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 'DEBIT')
    assertEquals(BigDecimal.ZERO.setScale(2), after)
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'unit.se.alipsa.accounting.service.AccountBalanceTest' 2>&1 | tail -10`
Expected: FAIL — `calculateBalanceAfter` method does not exist yet.

- [ ] **Step 3: Add `calculateAccountBalance()` and `calculateBalanceAfter()` to AccountService**

Add these methods to `AccountService.groovy`:

```groovy
/**
 * Calculates the account balance for a fiscal year, excluding a specific voucher.
 * Returns opening balance + net of all ACTIVE/CORRECTION voucher lines.
 */
BigDecimal calculateAccountBalance(long companyId, long fiscalYearId, String accountNumber, Long excludeVoucherId) {
  CompanyService.requireValidCompanyId(companyId)
  String normalized = normalizeAccountNumber(accountNumber)
  databaseService.withSql { Sql sql ->
    GroovyRowResult accountRow = sql.firstRow('''
        select id, normal_balance_side as normalBalanceSide
          from account
         where company_id = ?
           and account_number = ?
    ''', [companyId, normalized]) as GroovyRowResult
    if (accountRow == null) {
      return BigDecimal.ZERO.setScale(2)
    }
    long accountId = ((Number) accountRow.get('id')).longValue()
    String normalSide = accountRow.get('normalBalanceSide') as String

    GroovyRowResult openingRow = sql.firstRow('''
        select coalesce(amount, 0) as amount
          from opening_balance
         where fiscal_year_id = ?
           and account_id = ?
    ''', [fiscalYearId, accountId]) as GroovyRowResult
    BigDecimal opening = openingRow == null
        ? BigDecimal.ZERO
        : new BigDecimal(openingRow.get('amount').toString())

    StringBuilder query = new StringBuilder('''
        select coalesce(sum(vl.debit_amount), 0) as totalDebit,
               coalesce(sum(vl.credit_amount), 0) as totalCredit
          from voucher_line vl
          join voucher v on v.id = vl.voucher_id
         where vl.account_id = ?
           and v.fiscal_year_id = ?
           and v.status in ('ACTIVE', 'CORRECTION')
    ''')
    List<Object> params = [accountId, fiscalYearId]
    if (excludeVoucherId != null) {
      query.append(' and v.id != ?')
      params << excludeVoucherId
    }
    GroovyRowResult transactionRow = sql.firstRow(query.toString(), params) as GroovyRowResult
    BigDecimal totalDebit = new BigDecimal(transactionRow.get('totalDebit').toString())
    BigDecimal totalCredit = new BigDecimal(transactionRow.get('totalCredit').toString())

    BigDecimal net = normalSide == 'CREDIT'
        ? totalCredit.subtract(totalDebit)
        : totalDebit.subtract(totalCredit)
    opening.add(net).setScale(2)
  }
}

static BigDecimal calculateBalanceAfter(
    BigDecimal balanceBefore,
    BigDecimal debitAmount,
    BigDecimal creditAmount,
    String normalBalanceSide
) {
  BigDecimal safeBefore = balanceBefore ?: BigDecimal.ZERO
  BigDecimal safeDebit = debitAmount ?: BigDecimal.ZERO
  BigDecimal safeCredit = creditAmount ?: BigDecimal.ZERO
  BigDecimal net = normalBalanceSide == 'CREDIT'
      ? safeCredit.subtract(safeDebit)
      : safeDebit.subtract(safeCredit)
  safeBefore.add(net).setScale(2)
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'unit.se.alipsa.accounting.service.AccountBalanceTest' 2>&1 | tail -10`
Expected: PASS — all 5 tests green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/service/AccountService.groovy
git add app/src/test/groovy/unit/se/alipsa/accounting/service/AccountBalanceTest.groovy
git commit -m "lägg till kontosaldoberäkning i AccountService"
```

---

## Task 7: Extend ActiveCompanyManager with Fiscal Year

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/ui/ActiveCompanyManager.groovy`

- [ ] **Step 1: Add fiscal year tracking**

Edit `ActiveCompanyManager.groovy` to add fiscal year support:

```groovy
package se.alipsa.accounting.ui

import se.alipsa.accounting.domain.Company
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.service.CompanyService
import se.alipsa.accounting.service.FiscalYearService

import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport

/**
 * Observable holder for the active company and fiscal year. Panels listen for
 * changes to reload their data when the user switches company or fiscal year.
 */
final class ActiveCompanyManager {

  static final String COMPANY_ID_PROPERTY = 'companyId'
  static final String FISCAL_YEAR_PROPERTY = 'fiscalYear'

  private final CompanyService companyService
  private final FiscalYearService fiscalYearService
  private final PropertyChangeSupport support = new PropertyChangeSupport(this)
  private long companyId
  private FiscalYear fiscalYear

  ActiveCompanyManager(CompanyService companyService, FiscalYearService fiscalYearService) {
    this.companyService = companyService
    this.fiscalYearService = fiscalYearService
    try {
      List<Company> companies = companyService.listCompanies(true) ?: []
      this.companyId = companies.isEmpty() ? 0L : companies.first().id
      if (this.companyId > 0) {
        List<FiscalYear> years = fiscalYearService.listFiscalYears(this.companyId)
        this.fiscalYear = years.isEmpty() ? null : years.first()
      }
    } catch (Exception ignored) {
      this.companyId = 0L
    }
  }

  long getCompanyId() {
    companyId
  }

  void setCompanyId(long newCompanyId) {
    long old = this.companyId
    this.companyId = newCompanyId
    support.firePropertyChange(COMPANY_ID_PROPERTY, old, newCompanyId)
    reloadFiscalYears()
  }

  boolean hasActiveCompany() {
    companyId > 0
  }

  Company getActiveCompany() {
    companyId > 0 ? companyService.findById(companyId) : null
  }

  FiscalYear getFiscalYear() {
    fiscalYear
  }

  void setFiscalYear(FiscalYear newFiscalYear) {
    FiscalYear old = this.fiscalYear
    this.fiscalYear = newFiscalYear
    support.firePropertyChange(FISCAL_YEAR_PROPERTY, old, newFiscalYear)
  }

  List<FiscalYear> listFiscalYears() {
    companyId > 0 ? fiscalYearService.listFiscalYears(companyId) : []
  }

  private void reloadFiscalYears() {
    List<FiscalYear> years = listFiscalYears()
    FiscalYear newYear = years.isEmpty() ? null : years.first()
    setFiscalYear(newYear)
  }

  void addPropertyChangeListener(PropertyChangeListener listener) {
    support.addPropertyChangeListener(listener)
  }

  void removePropertyChangeListener(PropertyChangeListener listener) {
    support.removePropertyChangeListener(listener)
  }
}
```

- [ ] **Step 2: Update MainFrame constructor call**

In `MainFrame.groovy`, update the `ActiveCompanyManager` instantiation:

Change:
```groovy
private final ActiveCompanyManager activeCompanyManager = new ActiveCompanyManager(companyService)
```
To:
```groovy
private final ActiveCompanyManager activeCompanyManager = new ActiveCompanyManager(companyService, fiscalYearService)
```

- [ ] **Step 3: Compile check**

Run: `./gradlew compileGroovy 2>&1 | head -40`
Expected: Compilation errors in panels that construct `ActiveCompanyManager` with the old 1-arg constructor. These will be fixed when we update MainFrame fully.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/ui/ActiveCompanyManager.groovy
git commit -m "utöka ActiveCompanyManager med räkenskapsårsspårning"
```

---

## Task 8: Update i18n Message Files

**Files:**
- Modify: `app/src/main/resources/i18n/messages.properties`
- Modify: `app/src/main/resources/i18n/messages_sv.properties`

- [ ] **Step 1: Add new VoucherPanel keys and update existing keys**

Add to `messages.properties` (after the existing VoucherEditor section, replacing old VoucherListPanel keys):

```properties
# VoucherPanel
voucherPanel.label.voucherNumber=Voucher
voucherPanel.label.date=Date
voucherPanel.label.description=Description
voucherPanel.label.corrects=Corrects
voucherPanel.label.series=Series
voucherPanel.button.prev=\u25C0
voucherPanel.button.next=\u25B6
voucherPanel.button.new=+
voucherPanel.button.save=Save
voucherPanel.button.removeLine=Remove line
voucherPanel.button.createCorrection=Create correction
voucherPanel.button.void=Void
voucherPanel.button.addAttachment=Add attachment...
voucherPanel.button.openAttachment=Open attachment...
voucherPanel.tab.lines=Lines
voucherPanel.tab.attachments=Attachments
voucherPanel.tab.history=History
voucherPanel.table.account=Account
voucherPanel.table.accountDescription=Account description
voucherPanel.table.debit=Debit
voucherPanel.table.credit=Credit
voucherPanel.table.text=Text
voucherPanel.table.balanceBefore=Account balance before
voucherPanel.table.balanceAfter=Account balance after
voucherPanel.totals=Debit: {0}   Credit: {1}   Difference: {2}
voucherPanel.totals.invalid=Totals cannot be shown until all amounts are valid.
voucherPanel.message.saved=Voucher {0} saved.
voucherPanel.message.voided=Voucher {0} voided.
voucherPanel.message.correctionCreated=Correction created as {0}.
voucherPanel.error.saveFailed=The voucher could not be saved.
voucherPanel.error.voidFailed=The voucher could not be voided.
voucherPanel.error.correctionFailed=The correction could not be created.
voucherPanel.error.noFiscalYear=Select a fiscal year before registering vouchers.
voucherPanel.error.dateFormat=Date must be entered as yyyy-MM-dd.
voucherPanel.error.invalidAmount=Amounts must be valid numbers.
voucherPanel.error.periodLocked=The period is locked. Use a correction voucher.
voucherPanel.error.saveBeforeAttachment=Save the voucher before adding attachments.
voucherPanel.error.selectAttachment=Select an attachment first.
voucherPanel.message.attachmentAdded=Attachment {0} registered.
voucherPanel.error.attachmentFailed=The attachment could not be registered.
voucherPanel.lookup.noMatches=No matches
voucherPanel.label.jump=Go to

# Bottom bar
mainFrame.label.fiscalYear=Fiscal year
```

Add to `messages_sv.properties`:

```properties
# VoucherPanel
voucherPanel.label.voucherNumber=Verifikation
voucherPanel.label.date=Datum
voucherPanel.label.description=Beskrivning
voucherPanel.label.corrects=Korrigerar
voucherPanel.label.series=Serie
voucherPanel.button.prev=\u25C0
voucherPanel.button.next=\u25B6
voucherPanel.button.new=+
voucherPanel.button.save=Spara
voucherPanel.button.removeLine=Ta bort rad
voucherPanel.button.createCorrection=Skapa korrigering
voucherPanel.button.void=Makulera
voucherPanel.button.addAttachment=Lägg till bilaga...
voucherPanel.button.openAttachment=Öppna bilaga...
voucherPanel.tab.lines=Rader
voucherPanel.tab.attachments=Bilagor
voucherPanel.tab.history=Historik
voucherPanel.table.account=Konto
voucherPanel.table.accountDescription=Kontobeskrivning
voucherPanel.table.debit=Debet
voucherPanel.table.credit=Kredit
voucherPanel.table.text=Text
voucherPanel.table.balanceBefore=Kontosaldo före
voucherPanel.table.balanceAfter=Kontosaldo efter
voucherPanel.totals=Debet: {0}   Kredit: {1}   Differens: {2}
voucherPanel.totals.invalid=Summering kan inte visas innan alla belopp är giltiga.
voucherPanel.message.saved=Verifikation {0} sparades.
voucherPanel.message.voided=Verifikation {0} makulerades.
voucherPanel.message.correctionCreated=Korrigering skapad som {0}.
voucherPanel.error.saveFailed=Verifikationen kunde inte sparas.
voucherPanel.error.voidFailed=Verifikationen kunde inte makuleras.
voucherPanel.error.correctionFailed=Korrigeringen kunde inte skapas.
voucherPanel.error.noFiscalYear=Välj ett räkenskapsår innan verifikationer registreras.
voucherPanel.error.dateFormat=Datum måste anges som ÅÅÅÅ-MM-DD.
voucherPanel.error.invalidAmount=Belopp måste vara giltiga tal.
voucherPanel.error.periodLocked=Perioden är låst. Använd en korrigeringsverifikation.
voucherPanel.error.saveBeforeAttachment=Spara verifikationen innan du lägger till bilagor.
voucherPanel.error.selectAttachment=Välj en bilaga först.
voucherPanel.message.attachmentAdded=Bilagan {0} registrerades.
voucherPanel.error.attachmentFailed=Bilagan kunde inte registreras.
voucherPanel.lookup.noMatches=Inga träffar
voucherPanel.label.jump=Gå till

# Bottom bar
mainFrame.label.fiscalYear=Räkenskapsår
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/resources/i18n/messages.properties
git add app/src/main/resources/i18n/messages_sv.properties
git commit -m "lägg till i18n-nycklar för VoucherPanel och nedre statusfält"
```

---

## Task 9: Create AccountLookupPopup

**Files:**
- Create: `app/src/main/groovy/se/alipsa/accounting/ui/AccountLookupPopup.groovy`

- [ ] **Step 1: Implement the popup**

Create `app/src/main/groovy/se/alipsa/accounting/ui/AccountLookupPopup.groovy`:

```groovy
package se.alipsa.accounting.ui

import se.alipsa.accounting.domain.Account
import se.alipsa.accounting.service.AccountService
import se.alipsa.accounting.support.I18n

import java.awt.BorderLayout
import java.awt.Point
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.function.Consumer

import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Popup list for searching accounts by name or number.
 * Shown below a JTable cell during editing of the account description column.
 */
final class AccountLookupPopup {

  private static final int DEBOUNCE_MILLIS = 200
  private static final int MAX_VISIBLE_ROWS = 10

  private final AccountService accountService
  private final long companyId
  private final Consumer<Account> onSelect
  private final JPopupMenu popup = new JPopupMenu()
  private final DefaultListModel<String> listModel = new DefaultListModel<>()
  private final JList<String> resultList = new JList<>(listModel)
  private final Timer debounceTimer
  private List<Account> currentResults = []
  private JTextField activeEditor

  AccountLookupPopup(AccountService accountService, long companyId, Consumer<Account> onSelect) {
    this.accountService = accountService
    this.companyId = companyId
    this.onSelect = onSelect
    resultList.selectionMode = ListSelectionModel.SINGLE_SELECTION
    resultList.addMouseListener(new MouseAdapter() {
      @Override
      void mouseClicked(MouseEvent event) {
        if (event.clickCount == 1) {
          selectCurrent()
        }
      }
    })
    resultList.addKeyListener(new KeyAdapter() {
      @Override
      void keyPressed(KeyEvent event) {
        if (event.keyCode == KeyEvent.VK_ENTER) {
          selectCurrent()
          event.consume()
        } else if (event.keyCode == KeyEvent.VK_ESCAPE) {
          hide()
          event.consume()
        }
      }
    })
    JScrollPane scrollPane = new JScrollPane(resultList)
    popup.layout = new BorderLayout()
    popup.add(scrollPane, BorderLayout.CENTER)
    popup.focusable = false
    debounceTimer = new Timer(DEBOUNCE_MILLIS, { search() })
    debounceTimer.repeats = false
  }

  void attachToEditor(JTextField editor, JTable table, int row, int column) {
    activeEditor = editor
    editor.document.addDocumentListener(new DocumentListener() {
      @Override
      void insertUpdate(DocumentEvent event) { scheduleSearch() }
      @Override
      void removeUpdate(DocumentEvent event) { scheduleSearch() }
      @Override
      void changedUpdate(DocumentEvent event) { scheduleSearch() }
    })
    editor.addKeyListener(new KeyAdapter() {
      @Override
      void keyPressed(KeyEvent event) {
        if (event.keyCode == KeyEvent.VK_DOWN && popup.visible) {
          resultList.requestFocusInWindow()
          if (resultList.selectedIndex < 0 && listModel.size() > 0) {
            resultList.selectedIndex = 0
          }
          event.consume()
        } else if (event.keyCode == KeyEvent.VK_ESCAPE) {
          hide()
        } else if (event.keyCode == KeyEvent.VK_ENTER && popup.visible && currentResults.size() == 1) {
          resultList.selectedIndex = 0
          selectCurrent()
          event.consume()
        }
      }
    })
  }

  void hide() {
    popup.visible = false
    debounceTimer.stop()
  }

  private void scheduleSearch() {
    debounceTimer.restart()
  }

  private void search() {
    String query = activeEditor?.text?.trim()
    if (!query || query.length() < 1) {
      hide()
      return
    }
    currentResults = accountService.searchAccounts(companyId, query, null, true, false)
    listModel.clear()
    if (currentResults.isEmpty()) {
      listModel.addElement(I18n.instance.getString('voucherPanel.lookup.noMatches'))
      resultList.enabled = false
    } else {
      resultList.enabled = true
      currentResults.each { Account account ->
        listModel.addElement("${account.accountNumber} \u2014 ${account.accountName}" as String)
      }
    }
    resultList.visibleRowCount = Math.min(listModel.size(), MAX_VISIBLE_ROWS)
    popup.pack()
    if (activeEditor != null) {
      Point location = activeEditor.locationOnScreen
      popup.show(activeEditor, 0, activeEditor.height)
    }
  }

  private void selectCurrent() {
    int index = resultList.selectedIndex
    if (index >= 0 && index < currentResults.size()) {
      Account selected = currentResults[index]
      onSelect.accept(selected)
      hide()
    }
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/ui/AccountLookupPopup.groovy
git commit -m "skapa AccountLookupPopup för kontosökning"
```

---

## Task 10: Create VoucherPanel

This is the main UI task. VoucherPanel replaces both VoucherListPanel and VoucherEditor.

**Files:**
- Create: `app/src/main/groovy/se/alipsa/accounting/ui/VoucherPanel.groovy`

- [ ] **Step 1: Implement VoucherPanel**

Create `app/src/main/groovy/se/alipsa/accounting/ui/VoucherPanel.groovy`. This is a large file — the engineer should implement it following the design spec. Key structure:

```groovy
package se.alipsa.accounting.ui

import se.alipsa.accounting.domain.Account
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.Voucher
import se.alipsa.accounting.domain.VoucherLine
import se.alipsa.accounting.domain.VoucherStatus
import se.alipsa.accounting.service.AccountService
import se.alipsa.accounting.service.AttachmentService
import se.alipsa.accounting.service.AuditLogService
import se.alipsa.accounting.service.VoucherService
import se.alipsa.accounting.support.I18n

import java.awt.BorderLayout
import java.awt.FlowLayout
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import java.time.LocalDate
import java.time.format.DateTimeParseException

import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.TableModelEvent
import javax.swing.table.AbstractTableModel

// Import date picker
import com.github.lgooddatepicker.components.DatePicker
import com.github.lgooddatepicker.components.DatePickerSettings

/**
 * Inline voucher editor panel with navigation, replacing VoucherListPanel and VoucherEditor dialog.
 */
final class VoucherPanel extends JPanel implements PropertyChangeListener {

  private final VoucherService voucherService
  private final AccountService accountService
  private final AttachmentService attachmentService
  private final AuditLogService auditLogService
  private final ActiveCompanyManager activeCompanyManager

  // Header fields
  private JLabel voucherNumberLabel
  private DatePicker datePicker
  private JTextField descriptionField
  private JTextField seriesField
  private JLabel correctsLabel

  // Navigation
  private JButton prevButton
  private JButton nextButton
  private JTextField jumpField
  private JButton newButton
  private JButton saveButton
  private JButton correctionButton
  private JButton voidButton

  // Lines
  private LineTableModel lineTableModel
  private JTable lineTable
  private JLabel totalsLabel
  private JButton removeLineButton

  // Tabs
  private JTabbedPane tabs
  // Reuse existing AttachmentTableModel and AuditLogTableModel patterns

  // Feedback
  private JTextArea feedbackArea

  // State
  private Voucher currentVoucher
  private List<Voucher> voucherList = []
  private int currentIndex = -1

  VoucherPanel(
      VoucherService voucherService,
      AccountService accountService,
      AttachmentService attachmentService,
      AuditLogService auditLogService,
      ActiveCompanyManager activeCompanyManager
  ) {
    this.voucherService = voucherService
    this.accountService = accountService
    this.attachmentService = attachmentService
    this.auditLogService = auditLogService
    this.activeCompanyManager = activeCompanyManager
    I18n.instance.addLocaleChangeListener(this)
    activeCompanyManager.addPropertyChangeListener(this)
    buildUi()
    reloadVoucherList()
  }

  // ... implement buildUi(), buildHeaderPanel(), buildToolbar(), buildLinePanel(),
  //     navigation methods, save/void/correction logic, locale handling, etc.
  //
  // The LineTableModel has 7 columns:
  //   0: Account (editable)
  //   1: Account description (editable — triggers AccountLookupPopup)
  //   2: Debit (editable)
  //   3: Credit (editable)
  //   4: Text (editable)
  //   5: Account balance before (read-only, calculated)
  //   6: Account balance after (read-only, calculated)
  //
  // Navigation: prev/next step through voucherList, jumpField accepts voucher number
  // Save: calls voucherService.createVoucher() or updateVoucher(), then shows blank voucher
  // Read-only mode: when period is locked, all editing disabled
}
```

The engineer must implement the full panel following the patterns in the existing VoucherEditor and VoucherListPanel. Key behaviors:

1. **Header:** voucherNumber as read-only label, DatePicker for date (using LGoodDatePicker with keyboard editing disabled, like FiscalYearPanel), description text field, series text field, corrects label (visible only for corrections).

2. **Navigation toolbar:** prev/next buttons, jump-to text field, new (+) button, save button, create correction button, void button.

3. **LineTableModel:** 7-column table model. When account number is entered in column 0, lookup the account and fill column 1. When text is typed in column 1 (account description), show AccountLookupPopup and on selection fill both column 0 and 1. Columns 5-6 are read-only and computed via `AccountService.calculateAccountBalance()` and `AccountService.calculateBalanceAfter()`.

4. **Auto-row:** Always keep an empty row at the bottom. When a cell in the empty row is filled, add a new empty row.

5. **Save flow:** On save, if `currentVoucher == null`, call `voucherService.createVoucher()`. If `currentVoucher != null`, call `voucherService.updateVoucher()`. After save, reload the voucher list and present a new blank voucher.

6. **Period lock detection:** Before allowing edits, check if the voucher's accounting period is locked. If locked, disable all editing and show a message.

7. **PropertyChangeListener:** Listen for `COMPANY_ID_PROPERTY` and `FISCAL_YEAR_PROPERTY` changes to reload data.

- [ ] **Step 2: Compile check**

Run: `./gradlew compileGroovy 2>&1 | head -40`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/ui/VoucherPanel.groovy
git commit -m "skapa VoucherPanel med inline-redigering och navigation"
```

---

## Task 11: Update MainFrame — Bottom Bar and Tab Integration

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/ui/MainFrame.groovy`

- [ ] **Step 1: Update buildStatusBar() to include fiscal year selector**

Change `buildStatusBar()` to add a fiscal year combo box between the company selector and the status label:

```groovy
private JComboBox<FiscalYear> fiscalYearComboBox
private JLabel fiscalYearLabel

private JPanel buildStatusBar() {
  JPanel statusBar = new JPanel(new BorderLayout())
  statusBar.border = BorderFactory.createCompoundBorder(
      BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY),
      BorderFactory.createEmptyBorder(4, 8, 4, 8)
  )

  JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0))
  companyLabel = new JLabel(I18n.instance.getString('mainFrame.label.activeCompany'))
  companyComboBox = new JComboBox<>()
  reloadCompanyComboBox()
  companyComboBox.addActionListener { onCompanyComboBoxChanged() }
  fiscalYearLabel = new JLabel(I18n.instance.getString('mainFrame.label.fiscalYear'))
  fiscalYearComboBox = new JComboBox<>()
  reloadFiscalYearComboBox()
  fiscalYearComboBox.addActionListener { onFiscalYearComboBoxChanged() }
  leftPanel.add(companyLabel)
  leftPanel.add(companyComboBox)
  leftPanel.add(fiscalYearLabel)
  leftPanel.add(fiscalYearComboBox)

  statusLabel = new JLabel(I18n.instance.getString('mainFrame.status.ready'))
  statusLabel.border = BorderFactory.createEmptyBorder(0, 12, 0, 0)

  updateNotificationButton = new JButton(I18n.instance.getString('mainFrame.button.updateAvailable'))
  updateNotificationButton.visible = false
  updateNotificationButton.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
  updateNotificationButton.borderPainted = false
  updateNotificationButton.contentAreaFilled = false
  updateNotificationButton.foreground = new Color(22, 101, 52)
  updateNotificationButton.addActionListener { showUpdateDialog() }

  statusBar.add(leftPanel, BorderLayout.WEST)
  statusBar.add(statusLabel, BorderLayout.CENTER)
  statusBar.add(updateNotificationButton, BorderLayout.EAST)
  statusBar
}
```

Add these methods:

```groovy
private void reloadFiscalYearComboBox() {
  FiscalYear selected = fiscalYearComboBox?.selectedItem as FiscalYear
  java.awt.event.ActionListener[] listeners = fiscalYearComboBox.actionListeners
  listeners.each { fiscalYearComboBox.removeActionListener(it) }
  try {
    fiscalYearComboBox.removeAllItems()
    activeCompanyManager.listFiscalYears().each { FiscalYear fy ->
      fiscalYearComboBox.addItem(fy)
    }
    if (selected != null) {
      FiscalYear match = null
      for (int i = 0; i < fiscalYearComboBox.itemCount; i++) {
        FiscalYear item = fiscalYearComboBox.getItemAt(i)
        if (item.id == selected.id) { match = item; break }
      }
      if (match != null) { fiscalYearComboBox.selectedItem = match }
    } else if (fiscalYearComboBox.itemCount > 0) {
      fiscalYearComboBox.selectedIndex = 0
    }
  } finally {
    listeners.each { fiscalYearComboBox.addActionListener(it) }
  }
}

private void onFiscalYearComboBoxChanged() {
  FiscalYear selected = fiscalYearComboBox.selectedItem as FiscalYear
  if (selected != null) {
    activeCompanyManager.setFiscalYear(selected)
  }
}
```

- [ ] **Step 2: Replace VoucherListPanel with VoucherPanel in buildMainTabs()**

Change the vouchers tab entry from:

```groovy
[title: I18n.instance.getString('mainFrame.tab.vouchers'), component: new VoucherListPanel(voucherService, fiscalYearService, accountService, attachmentService, auditLogService, activeCompanyManager)],
```

To:

```groovy
[title: I18n.instance.getString('mainFrame.tab.vouchers'), component: new VoucherPanel(voucherService, accountService, attachmentService, auditLogService, activeCompanyManager)],
```

Also remove the `VoucherEditor.Dependencies` construction from the `ReportPanel` line if it references VoucherEditor — the ReportPanel may need its own adaptation (check if it still opens VoucherEditor dialogs for drill-down).

- [ ] **Step 3: Update onCompanyChanged() to reload fiscal years**

```groovy
private void onCompanyChanged() {
  refreshTitle()
  refreshCompanySettingsSummary()
  reloadFiscalYearComboBox()
  Company active = activeCompanyManager.activeCompany
  if (active != null) {
    setStatus(I18n.instance.format('mainFrame.status.companySwitched', active.companyName))
  }
}
```

- [ ] **Step 4: Update applyLocale()**

Add to the `applyLocale()` method:
```groovy
fiscalYearLabel.text = I18n.instance.getString('mainFrame.label.fiscalYear')
```

- [ ] **Step 5: Add FiscalYear import**

Add at the top of MainFrame.groovy:
```groovy
import se.alipsa.accounting.domain.FiscalYear
```

- [ ] **Step 6: Compile and run build**

Run: `./gradlew spotlessApply && ./gradlew build 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/ui/MainFrame.groovy
git commit -m "flytta företag och räkenskapsår till nedre statusfältet, integrera VoucherPanel"
```

---

## Task 12: Delete Old Voucher UI Files

**Files:**
- Delete: `app/src/main/groovy/se/alipsa/accounting/ui/VoucherListPanel.groovy`
- Delete: `app/src/main/groovy/se/alipsa/accounting/ui/VoucherEditor.groovy`

- [ ] **Step 1: Verify no remaining references**

Run: `grep -r 'VoucherListPanel\|VoucherEditor' app/src/main/groovy/ --include='*.groovy' -l`

Expected: No files reference these classes (MainFrame now uses VoucherPanel). If ReportPanel still references VoucherEditor.Dependencies, it needs to be updated first.

- [ ] **Step 2: Handle ReportPanel dependency on VoucherEditor.Dependencies**

If ReportPanel uses `VoucherEditor.Dependencies`, extract that class to its own file or adjust ReportPanel to accept individual services directly. Read `ReportPanel.groovy` to determine the exact usage and fix it.

- [ ] **Step 3: Delete old files**

```bash
git rm app/src/main/groovy/se/alipsa/accounting/ui/VoucherListPanel.groovy
git rm app/src/main/groovy/se/alipsa/accounting/ui/VoucherEditor.groovy
```

- [ ] **Step 4: Run full build**

Run: `./gradlew spotlessApply && ./gradlew build 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git commit -m "ta bort VoucherListPanel och VoucherEditor — ersatta av VoucherPanel"
```

---

## Task 13: Update Existing Tests

**Files:**
- Modify: `app/src/test/groovy/unit/se/alipsa/accounting/service/VoucherServiceUnitTest.groovy`
- Remove or update any tests that reference `DRAFT`, `BOOKED`, `bookDraft`, `createAndBook`, `validateIntegrity`

- [ ] **Step 1: Check for affected tests**

Run: `grep -r 'DRAFT\|BOOKED\|bookDraft\|createAndBook\|validateIntegrity' app/src/test/ --include='*.groovy' -l`

Update or remove test methods that reference removed concepts. Keep the `normalizeAccountNumber` tests — they are still valid.

- [ ] **Step 2: Run full test suite**

Run: `./gradlew test 2>&1 | tail -20`
Expected: All tests pass.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/
git commit -m "uppdatera tester för förenklad verifikationslivscykel"
```

---

## Task 14: Final Build and Format

- [ ] **Step 1: Run spotlessApply**

Run: `./gradlew spotlessApply`

- [ ] **Step 2: Run full build**

Run: `./gradlew build 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL with all tests passing, CodeNarc clean, Spotless clean.

- [ ] **Step 3: Commit any formatting changes**

```bash
git add -A
git commit -m "spotlessApply: formatering"
```
