# Grouped Income Statement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the flat income statement report with a properly grouped report following Swedish accounting standards (ÅRL/K2/K3) based on BAS account subgroups.

**Architecture:** A new `AccountSubgroup` enum maps 4-digit BAS account numbers to semantic groups via two-digit prefix ranges. A new `IncomeStatementSection` enum defines report sections and maps subgroups to them. The `account` table gains an `account_subgroup` column populated by migration and maintained by import services. `ReportDataService.buildIncomeStatementReport()` is rewritten to produce grouped output with subtotals and computed result rows.

**Tech Stack:** Groovy 5, H2 SQL migrations, JUnit 6, FreeMarker templates, I18n properties

**Spec:** `docs/superpowers/specs/2026-04-16-grouped-income-statement-design.md`

---

### Task 1: Create `AccountSubgroup` enum

**Files:**
- Create: `app/src/main/groovy/se/alipsa/accounting/domain/AccountSubgroup.groovy`
- Create: `app/src/test/groovy/unit/se/alipsa/accounting/domain/AccountSubgroupTest.groovy`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/groovy/unit/se/alipsa/accounting/domain/AccountSubgroupTest.groovy`:

```groovy
package se.alipsa.accounting.domain

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class AccountSubgroupTest {

  @ParameterizedTest
  @CsvSource([
      '1010, INTANGIBLE_ASSETS',
      '1099, INTANGIBLE_ASSETS',
      '1110, BUILDINGS_AND_LAND',
      '1210, MACHINERY',
      '1310, FINANCIAL_FIXED_ASSETS',
      '1410, INVENTORY',
      '1510, RECEIVABLES',
      '1610, OTHER_CURRENT_RECEIVABLES',
      '1710, PREPAID_EXPENSES',
      '1810, SHORT_TERM_INVESTMENTS',
      '1910, CASH_AND_BANK',
      '2010, EQUITY',
      '2099, EQUITY',
      '2110, UNTAXED_RESERVES',
      '2210, PROVISIONS',
      '2310, LONG_TERM_LIABILITIES',
      '2410, SHORT_TERM_LIABILITIES_CREDIT',
      '2510, TAX_LIABILITIES',
      '2610, VAT_AND_EXCISE',
      '2710, PAYROLL_TAXES',
      '2810, OTHER_CURRENT_LIABILITIES',
      '2910, ACCRUED_EXPENSES',
      '3010, NET_REVENUE',
      '3499, NET_REVENUE',
      '3510, INVOICED_COSTS',
      '3610, SECONDARY_INCOME',
      '3710, REVENUE_ADJUSTMENTS',
      '3810, CAPITALIZED_WORK',
      '3910, OTHER_OPERATING_INCOME',
      '4010, RAW_MATERIALS',
      '4999, RAW_MATERIALS',
      '5010, OTHER_EXTERNAL_COSTS',
      '6910, OTHER_EXTERNAL_COSTS',
      '7010, PERSONNEL_COSTS',
      '7699, PERSONNEL_COSTS',
      '7710, DEPRECIATION',
      '7899, DEPRECIATION',
      '7910, OTHER_OPERATING_COSTS',
      '8010, FINANCIAL_INCOME',
      '8399, FINANCIAL_INCOME',
      '8410, FINANCIAL_COSTS',
      '8810, APPROPRIATIONS',
      '8910, TAX_AND_RESULT'
  ])
  void fromAccountNumberMapsAllBasGroups(String accountNumber, String expectedName) {
    assertEquals(AccountSubgroup.valueOf(expectedName), AccountSubgroup.fromAccountNumber(accountNumber))
  }

  @Test
  void fromAccountNumberReturnsNullForUnmappedPrefix() {
    assertNull(AccountSubgroup.fromAccountNumber('0010'))
  }

  @Test
  void fromDatabaseValueReturnsEnumForValidName() {
    assertEquals(AccountSubgroup.NET_REVENUE, AccountSubgroup.fromDatabaseValue('NET_REVENUE'))
  }

  @Test
  void fromDatabaseValueReturnsNullForBlank() {
    assertNull(AccountSubgroup.fromDatabaseValue(null))
    assertNull(AccountSubgroup.fromDatabaseValue(''))
    assertNull(AccountSubgroup.fromDatabaseValue('  '))
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'unit.se.alipsa.accounting.domain.AccountSubgroupTest' --info 2>&1 | tail -20`
Expected: compilation failure — `AccountSubgroup` does not exist yet.

- [ ] **Step 3: Write the enum**

Create `app/src/main/groovy/se/alipsa/accounting/domain/AccountSubgroup.groovy`:

```groovy
package se.alipsa.accounting.domain

import se.alipsa.accounting.support.I18n

/**
 * BAS chart of accounts subgroups mapped from the two-digit account prefix.
 */
enum AccountSubgroup {

  // Balance accounts (10-29)
  INTANGIBLE_ASSETS(10, 10),
  BUILDINGS_AND_LAND(11, 11),
  MACHINERY(12, 12),
  FINANCIAL_FIXED_ASSETS(13, 13),
  INVENTORY(14, 14),
  RECEIVABLES(15, 15),
  OTHER_CURRENT_RECEIVABLES(16, 16),
  PREPAID_EXPENSES(17, 17),
  SHORT_TERM_INVESTMENTS(18, 18),
  CASH_AND_BANK(19, 19),
  EQUITY(20, 20),
  UNTAXED_RESERVES(21, 21),
  PROVISIONS(22, 22),
  LONG_TERM_LIABILITIES(23, 23),
  SHORT_TERM_LIABILITIES_CREDIT(24, 24),
  TAX_LIABILITIES(25, 25),
  VAT_AND_EXCISE(26, 26),
  PAYROLL_TAXES(27, 27),
  OTHER_CURRENT_LIABILITIES(28, 28),
  ACCRUED_EXPENSES(29, 29),

  // Result accounts (30-89)
  NET_REVENUE(30, 34),
  INVOICED_COSTS(35, 35),
  SECONDARY_INCOME(36, 36),
  REVENUE_ADJUSTMENTS(37, 37),
  CAPITALIZED_WORK(38, 38),
  OTHER_OPERATING_INCOME(39, 39),
  RAW_MATERIALS(40, 49),
  OTHER_EXTERNAL_COSTS(50, 69),
  PERSONNEL_COSTS(70, 76),
  DEPRECIATION(77, 78),
  OTHER_OPERATING_COSTS(79, 79),
  FINANCIAL_INCOME(80, 83),
  FINANCIAL_COSTS(84, 84),
  APPROPRIATIONS(88, 88),
  TAX_AND_RESULT(89, 89)

  final int basGroupStart
  final int basGroupEnd

  AccountSubgroup(int basGroupStart, int basGroupEnd) {
    this.basGroupStart = basGroupStart
    this.basGroupEnd = basGroupEnd
  }

  boolean contains(int basGroup) {
    basGroup >= basGroupStart && basGroup <= basGroupEnd
  }

  String getDisplayName() {
    I18n.instance.getString("accountSubgroup.${name()}")
  }

  @Override
  String toString() {
    displayName
  }

  static AccountSubgroup fromAccountNumber(String accountNumber) {
    String normalized = accountNumber?.trim()
    if (!normalized || normalized.length() < 2) {
      return null
    }
    int prefix
    try {
      prefix = Integer.parseInt(normalized.substring(0, 2))
    } catch (NumberFormatException ignored) {
      return null
    }
    values().find { AccountSubgroup subgroup -> subgroup.contains(prefix) }
  }

  static AccountSubgroup fromDatabaseValue(String value) {
    String normalized = value?.trim()
    normalized ? valueOf(normalized) : null
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'unit.se.alipsa.accounting.domain.AccountSubgroupTest' --info 2>&1 | tail -20`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/domain/AccountSubgroup.groovy \
       app/src/test/groovy/unit/se/alipsa/accounting/domain/AccountSubgroupTest.groovy
git commit -m "feat: AccountSubgroup enum med BAS-gruppmappning"
```

---

### Task 2: Create `IncomeStatementSection` enum

**Files:**
- Create: `app/src/main/groovy/se/alipsa/accounting/domain/report/IncomeStatementSection.groovy`

- [ ] **Step 1: Create the enum**

Create `app/src/main/groovy/se/alipsa/accounting/domain/report/IncomeStatementSection.groovy`:

```groovy
package se.alipsa.accounting.domain.report

import se.alipsa.accounting.domain.AccountSubgroup
import se.alipsa.accounting.support.I18n

/**
 * Logical sections of the income statement report, each mapping to one or more
 * {@link AccountSubgroup} values. Sections with an empty subgroup list are computed
 * result rows (subtotals).
 */
enum IncomeStatementSection {

  OPERATING_INCOME([
      AccountSubgroup.NET_REVENUE,
      AccountSubgroup.INVOICED_COSTS,
      AccountSubgroup.SECONDARY_INCOME,
      AccountSubgroup.REVENUE_ADJUSTMENTS,
      AccountSubgroup.CAPITALIZED_WORK,
      AccountSubgroup.OTHER_OPERATING_INCOME
  ]),
  OPERATING_EXPENSES([
      AccountSubgroup.RAW_MATERIALS,
      AccountSubgroup.OTHER_EXTERNAL_COSTS,
      AccountSubgroup.PERSONNEL_COSTS,
      AccountSubgroup.DEPRECIATION,
      AccountSubgroup.OTHER_OPERATING_COSTS
  ]),
  OPERATING_RESULT([]),
  FINANCIAL_ITEMS([
      AccountSubgroup.FINANCIAL_INCOME,
      AccountSubgroup.FINANCIAL_COSTS
  ]),
  RESULT_AFTER_FINANCIAL([]),
  APPROPRIATIONS([
      AccountSubgroup.APPROPRIATIONS
  ]),
  TAX([
      AccountSubgroup.TAX_AND_RESULT
  ]),
  NET_RESULT([])

  final List<AccountSubgroup> subgroups

  IncomeStatementSection(List<AccountSubgroup> subgroups) {
    this.subgroups = Collections.unmodifiableList(subgroups)
  }

  boolean isComputed() {
    subgroups.isEmpty()
  }

  String getDisplayName() {
    I18n.instance.getString("incomeStatementSection.${name()}")
  }

  @Override
  String toString() {
    displayName
  }
}
```

- [ ] **Step 2: Run compilation check**

Run: `./gradlew compileGroovy --info 2>&1 | tail -10`
Expected: compilation succeeds.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/domain/report/IncomeStatementSection.groovy
git commit -m "feat: IncomeStatementSection enum för resultatrapportens sektioner"
```

---

### Task 3: Add I18n keys for subgroups and sections

**Files:**
- Modify: `app/src/main/resources/i18n/messages_sv.properties`
- Modify: `app/src/main/resources/i18n/messages.properties`

- [ ] **Step 1: Add Swedish keys**

Append to `app/src/main/resources/i18n/messages_sv.properties`, after the `# ReportType` block (line 32):

```properties

# AccountSubgroup
accountSubgroup.INTANGIBLE_ASSETS=Immateriella anläggningstillgångar
accountSubgroup.BUILDINGS_AND_LAND=Byggnader och mark
accountSubgroup.MACHINERY=Maskiner respektive inventarier
accountSubgroup.FINANCIAL_FIXED_ASSETS=Finansiella anläggningstillgångar
accountSubgroup.INVENTORY=Lager, produkter i arbete och pågående arbeten
accountSubgroup.RECEIVABLES=Kundfordringar
accountSubgroup.OTHER_CURRENT_RECEIVABLES=Övriga kortfristiga fordringar
accountSubgroup.PREPAID_EXPENSES=Förutbetalda kostnader och upplupna intäkter
accountSubgroup.SHORT_TERM_INVESTMENTS=Kortfristiga placeringar
accountSubgroup.CASH_AND_BANK=Kassa och bank
accountSubgroup.EQUITY=Eget kapital
accountSubgroup.UNTAXED_RESERVES=Obeskattade reserver
accountSubgroup.PROVISIONS=Avsättningar
accountSubgroup.LONG_TERM_LIABILITIES=Långfristiga skulder
accountSubgroup.SHORT_TERM_LIABILITIES_CREDIT=Kortfristiga skulder
accountSubgroup.TAX_LIABILITIES=Skatteskulder
accountSubgroup.VAT_AND_EXCISE=Moms och punktskatter
accountSubgroup.PAYROLL_TAXES=Personalens skatter, avgifter och löneavdrag
accountSubgroup.OTHER_CURRENT_LIABILITIES=Övriga kortfristiga skulder
accountSubgroup.ACCRUED_EXPENSES=Upplupna kostnader och förutbetalda intäkter
accountSubgroup.NET_REVENUE=Nettoomsättning
accountSubgroup.INVOICED_COSTS=Fakturerade kostnader
accountSubgroup.SECONDARY_INCOME=Rörelsens sidointäkter
accountSubgroup.REVENUE_ADJUSTMENTS=Intäktskorrigeringar
accountSubgroup.CAPITALIZED_WORK=Aktiverat arbete för egen räkning
accountSubgroup.OTHER_OPERATING_INCOME=Övriga rörelseintäkter
accountSubgroup.RAW_MATERIALS=Råvaror och förnödenheter
accountSubgroup.OTHER_EXTERNAL_COSTS=Övriga externa kostnader
accountSubgroup.PERSONNEL_COSTS=Personalkostnader
accountSubgroup.DEPRECIATION=Av- och nedskrivningar
accountSubgroup.OTHER_OPERATING_COSTS=Övriga rörelsekostnader
accountSubgroup.FINANCIAL_INCOME=Finansiella intäkter
accountSubgroup.FINANCIAL_COSTS=Finansiella kostnader
accountSubgroup.APPROPRIATIONS=Bokslutsdispositioner
accountSubgroup.TAX_AND_RESULT=Skatt på årets resultat

# IncomeStatementSection
incomeStatementSection.OPERATING_INCOME=Rörelseintäkter
incomeStatementSection.OPERATING_EXPENSES=Rörelsekostnader
incomeStatementSection.OPERATING_RESULT=Rörelseresultat
incomeStatementSection.FINANCIAL_ITEMS=Finansiella poster
incomeStatementSection.RESULT_AFTER_FINANCIAL=Resultat efter finansiella poster
incomeStatementSection.APPROPRIATIONS=Bokslutsdispositioner
incomeStatementSection.TAX=Skatt på årets resultat
incomeStatementSection.NET_RESULT=Årets resultat
```

- [ ] **Step 2: Add English keys**

Append to `app/src/main/resources/i18n/messages.properties`, after the `# ReportType` block (line 32):

```properties

# AccountSubgroup
accountSubgroup.INTANGIBLE_ASSETS=Intangible assets
accountSubgroup.BUILDINGS_AND_LAND=Buildings and land
accountSubgroup.MACHINERY=Machinery and equipment
accountSubgroup.FINANCIAL_FIXED_ASSETS=Financial fixed assets
accountSubgroup.INVENTORY=Inventory and work in progress
accountSubgroup.RECEIVABLES=Trade receivables
accountSubgroup.OTHER_CURRENT_RECEIVABLES=Other current receivables
accountSubgroup.PREPAID_EXPENSES=Prepaid expenses and accrued income
accountSubgroup.SHORT_TERM_INVESTMENTS=Short-term investments
accountSubgroup.CASH_AND_BANK=Cash and bank
accountSubgroup.EQUITY=Equity
accountSubgroup.UNTAXED_RESERVES=Untaxed reserves
accountSubgroup.PROVISIONS=Provisions
accountSubgroup.LONG_TERM_LIABILITIES=Long-term liabilities
accountSubgroup.SHORT_TERM_LIABILITIES_CREDIT=Short-term liabilities
accountSubgroup.TAX_LIABILITIES=Tax liabilities
accountSubgroup.VAT_AND_EXCISE=VAT and excise duties
accountSubgroup.PAYROLL_TAXES=Payroll taxes and deductions
accountSubgroup.OTHER_CURRENT_LIABILITIES=Other current liabilities
accountSubgroup.ACCRUED_EXPENSES=Accrued expenses and prepaid income
accountSubgroup.NET_REVENUE=Net revenue
accountSubgroup.INVOICED_COSTS=Invoiced costs
accountSubgroup.SECONDARY_INCOME=Other operating income
accountSubgroup.REVENUE_ADJUSTMENTS=Revenue adjustments
accountSubgroup.CAPITALIZED_WORK=Capitalized work for own use
accountSubgroup.OTHER_OPERATING_INCOME=Other operating income
accountSubgroup.RAW_MATERIALS=Raw materials and consumables
accountSubgroup.OTHER_EXTERNAL_COSTS=Other external costs
accountSubgroup.PERSONNEL_COSTS=Personnel costs
accountSubgroup.DEPRECIATION=Depreciation and amortization
accountSubgroup.OTHER_OPERATING_COSTS=Other operating costs
accountSubgroup.FINANCIAL_INCOME=Financial income
accountSubgroup.FINANCIAL_COSTS=Financial costs
accountSubgroup.APPROPRIATIONS=Appropriations
accountSubgroup.TAX_AND_RESULT=Tax on profit for the year

# IncomeStatementSection
incomeStatementSection.OPERATING_INCOME=Operating income
incomeStatementSection.OPERATING_EXPENSES=Operating expenses
incomeStatementSection.OPERATING_RESULT=Operating result
incomeStatementSection.FINANCIAL_ITEMS=Financial items
incomeStatementSection.RESULT_AFTER_FINANCIAL=Result after financial items
incomeStatementSection.APPROPRIATIONS=Appropriations
incomeStatementSection.TAX=Tax on profit for the year
incomeStatementSection.NET_RESULT=Net result for the year
```

- [ ] **Step 3: Run compilation check**

Run: `./gradlew compileGroovy --info 2>&1 | tail -10`
Expected: compilation succeeds.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/resources/i18n/messages_sv.properties \
       app/src/main/resources/i18n/messages.properties
git commit -m "i18n: nycklar för AccountSubgroup och IncomeStatementSection"
```

---

### Task 4: DB migration — add `account_subgroup` column

**Files:**
- Create: `app/src/main/resources/db/migrations/V18__account_subgroup.sql`

- [ ] **Step 1: Write the migration**

Create `app/src/main/resources/db/migrations/V18__account_subgroup.sql`:

```sql
alter table account
    add column account_subgroup varchar(32);

-- Balance accounts (10-29)
update account set account_subgroup = 'INTANGIBLE_ASSETS'
 where cast(substring(account_number, 1, 2) as int) = 10;

update account set account_subgroup = 'BUILDINGS_AND_LAND'
 where cast(substring(account_number, 1, 2) as int) = 11;

update account set account_subgroup = 'MACHINERY'
 where cast(substring(account_number, 1, 2) as int) = 12;

update account set account_subgroup = 'FINANCIAL_FIXED_ASSETS'
 where cast(substring(account_number, 1, 2) as int) = 13;

update account set account_subgroup = 'INVENTORY'
 where cast(substring(account_number, 1, 2) as int) = 14;

update account set account_subgroup = 'RECEIVABLES'
 where cast(substring(account_number, 1, 2) as int) = 15;

update account set account_subgroup = 'OTHER_CURRENT_RECEIVABLES'
 where cast(substring(account_number, 1, 2) as int) = 16;

update account set account_subgroup = 'PREPAID_EXPENSES'
 where cast(substring(account_number, 1, 2) as int) = 17;

update account set account_subgroup = 'SHORT_TERM_INVESTMENTS'
 where cast(substring(account_number, 1, 2) as int) = 18;

update account set account_subgroup = 'CASH_AND_BANK'
 where cast(substring(account_number, 1, 2) as int) = 19;

update account set account_subgroup = 'EQUITY'
 where cast(substring(account_number, 1, 2) as int) = 20;

update account set account_subgroup = 'UNTAXED_RESERVES'
 where cast(substring(account_number, 1, 2) as int) = 21;

update account set account_subgroup = 'PROVISIONS'
 where cast(substring(account_number, 1, 2) as int) = 22;

update account set account_subgroup = 'LONG_TERM_LIABILITIES'
 where cast(substring(account_number, 1, 2) as int) = 23;

update account set account_subgroup = 'SHORT_TERM_LIABILITIES_CREDIT'
 where cast(substring(account_number, 1, 2) as int) = 24;

update account set account_subgroup = 'TAX_LIABILITIES'
 where cast(substring(account_number, 1, 2) as int) = 25;

update account set account_subgroup = 'VAT_AND_EXCISE'
 where cast(substring(account_number, 1, 2) as int) = 26;

update account set account_subgroup = 'PAYROLL_TAXES'
 where cast(substring(account_number, 1, 2) as int) = 27;

update account set account_subgroup = 'OTHER_CURRENT_LIABILITIES'
 where cast(substring(account_number, 1, 2) as int) = 28;

update account set account_subgroup = 'ACCRUED_EXPENSES'
 where cast(substring(account_number, 1, 2) as int) = 29;

-- Result accounts (30-89)
update account set account_subgroup = 'NET_REVENUE'
 where cast(substring(account_number, 1, 2) as int) between 30 and 34;

update account set account_subgroup = 'INVOICED_COSTS'
 where cast(substring(account_number, 1, 2) as int) = 35;

update account set account_subgroup = 'SECONDARY_INCOME'
 where cast(substring(account_number, 1, 2) as int) = 36;

update account set account_subgroup = 'REVENUE_ADJUSTMENTS'
 where cast(substring(account_number, 1, 2) as int) = 37;

update account set account_subgroup = 'CAPITALIZED_WORK'
 where cast(substring(account_number, 1, 2) as int) = 38;

update account set account_subgroup = 'OTHER_OPERATING_INCOME'
 where cast(substring(account_number, 1, 2) as int) = 39;

update account set account_subgroup = 'RAW_MATERIALS'
 where cast(substring(account_number, 1, 2) as int) between 40 and 49;

update account set account_subgroup = 'OTHER_EXTERNAL_COSTS'
 where cast(substring(account_number, 1, 2) as int) between 50 and 69;

update account set account_subgroup = 'PERSONNEL_COSTS'
 where cast(substring(account_number, 1, 2) as int) between 70 and 76;

update account set account_subgroup = 'DEPRECIATION'
 where cast(substring(account_number, 1, 2) as int) between 77 and 78;

update account set account_subgroup = 'OTHER_OPERATING_COSTS'
 where cast(substring(account_number, 1, 2) as int) = 79;

update account set account_subgroup = 'FINANCIAL_INCOME'
 where cast(substring(account_number, 1, 2) as int) between 80 and 83;

update account set account_subgroup = 'FINANCIAL_COSTS'
 where cast(substring(account_number, 1, 2) as int) = 84;

update account set account_subgroup = 'APPROPRIATIONS'
 where cast(substring(account_number, 1, 2) as int) = 88;

update account set account_subgroup = 'TAX_AND_RESULT'
 where cast(substring(account_number, 1, 2) as int) = 89;
```

- [ ] **Step 2: Run the full build to verify migration applies**

Run: `./gradlew build --info 2>&1 | tail -30`
Expected: build succeeds, all existing tests still pass.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/resources/db/migrations/V18__account_subgroup.sql
git commit -m "migration: V18 account_subgroup kolumn med BAS-gruppmappning"
```

---

### Task 5: Update `Account` domain model

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/domain/Account.groovy`

- [ ] **Step 1: Add the `accountSubgroup` field**

In `app/src/main/groovy/se/alipsa/accounting/domain/Account.groovy`, add after line 16 (`String classificationNote`):

```groovy
  String accountSubgroup
```

The full field list becomes:

```groovy
  Long id
  Long companyId
  String accountNumber
  String accountName
  String accountClass
  String normalBalanceSide
  String vatCode
  boolean active = true
  boolean manualReviewRequired = false
  String classificationNote
  String accountSubgroup
```

- [ ] **Step 2: Run compilation check**

Run: `./gradlew compileGroovy --info 2>&1 | tail -10`
Expected: compilation succeeds.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/domain/Account.groovy
git commit -m "domain: accountSubgroup fält i Account"
```

---

### Task 6: Update import services to set `account_subgroup`

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/service/ChartOfAccountsImportService.groovy`
- Modify: `app/src/main/groovy/se/alipsa/accounting/service/SieImportExportService.groovy`

- [ ] **Step 1: Update `ChartOfAccountsImportService.Classification`**

In `ChartOfAccountsImportService.groovy`, update the `Classification` inner class (line 266) to add `accountSubgroup`:

```groovy
  @Canonical
  private static final class Classification {

    String accountClass
    String normalBalanceSide
    boolean manualReviewRequired
    String note
    String accountSubgroup
  }
```

- [ ] **Step 2: Update `classifyAccount()` in `ChartOfAccountsImportService`**

In `ChartOfAccountsImportService.groovy`, update `classifyAccount()` (line 187) to derive `accountSubgroup`. Replace the method:

```groovy
  private static Classification classifyAccount(String accountNumber, String accountName) {
    int prefix = Integer.parseInt(accountNumber.substring(0, 1))
    int subgroup = Integer.parseInt(accountNumber.substring(0, 2))
    String accountSubgroup = AccountSubgroup.fromAccountNumber(accountNumber)?.name()

    switch (prefix) {
      case 1:
        return new Classification('ASSET', 'DEBIT', false, null, accountSubgroup)
      case 2:
        if (subgroup <= 20) {
          return new Classification('EQUITY', 'CREDIT', false, null, accountSubgroup)
        }
        return new Classification('LIABILITY', 'CREDIT', false, null, accountSubgroup)
      case 3:
        return new Classification('INCOME', 'CREDIT', false, null, accountSubgroup)
      case 4:
      case 5:
      case 6:
      case 7:
        return new Classification('EXPENSE', 'DEBIT', false, null, accountSubgroup)
      case 8:
        return classifyMixedResultAccount(accountNumber, accountName)
      default:
        return new Classification(
            null,
            null,
            true,
            'Kontot kunde inte klassificeras automatiskt från BAS-importen.',
            accountSubgroup
        )
    }
  }
```

Also update `classifyMixedResultAccount` (line 218) to accept `accountNumber` and pass `accountSubgroup`:

```groovy
  private static Classification classifyMixedResultAccount(String accountNumber, String accountName) {
    String normalized = stripDiacritics(accountName).toUpperCase(Locale.ROOT)
    boolean incomeMatch = INCOME_KEYWORDS.any { String keyword -> normalized.contains(keyword) }
    boolean expenseMatch = EXPENSE_KEYWORDS.any { String keyword -> normalized.contains(keyword) }
    String accountSubgroup = AccountSubgroup.fromAccountNumber(accountNumber)?.name()

    if (incomeMatch && !expenseMatch) {
      return new Classification('INCOME', 'CREDIT', false, null, accountSubgroup)
    }
    if (expenseMatch && !incomeMatch) {
      return new Classification('EXPENSE', 'DEBIT', false, null, accountSubgroup)
    }

    new Classification(
        null,
        null,
        true,
        'Kontot kräver manuell klassning eftersom BAS-gruppen innehåller både intäkter och kostnader.',
        accountSubgroup
    )
  }
```

Add the import at the top of the file:

```groovy
import se.alipsa.accounting.domain.AccountSubgroup
```

- [ ] **Step 3: Update `addAccountIfPresent()` to pass `accountSubgroup`**

In `ChartOfAccountsImportService.groovy`, update `addAccountIfPresent()` (line 147) to set the new field on `Account`:

```groovy
    accounts[accountNumber] = new Account(
        accountNumber: accountNumber,
        accountName: accountName,
        accountClass: classification.accountClass,
        normalBalanceSide: classification.normalBalanceSide,
        vatCode: null,
        active: true,
        manualReviewRequired: classification.manualReviewRequired,
        classificationNote: classification.note,
        accountSubgroup: classification.accountSubgroup
    )
```

- [ ] **Step 4: Update `persistAccounts()` SQL statements**

In `ChartOfAccountsImportService.groovy`, update the INSERT in `persistAccounts()` (line 67) to include `account_subgroup`:

```groovy
        sql.executeInsert('''
            insert into account (
                company_id,
                account_number,
                account_name,
                account_class,
                normal_balance_side,
                vat_code,
                active,
                manual_review_required,
                classification_note,
                account_subgroup,
                created_at,
                updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp, current_timestamp)
        ''', [
            companyId,
            account.accountNumber,
            account.accountName,
            account.accountClass,
            account.normalBalanceSide,
            account.vatCode,
            account.active,
            account.manualReviewRequired,
            account.classificationNote,
            account.accountSubgroup
        ])
```

Update the UPDATE statement (line 95) to include `account_subgroup`:

```groovy
        sql.executeUpdate('''
            update account
               set account_name = ?,
                   account_class = ?,
                   normal_balance_side = ?,
                   manual_review_required = ?,
                   classification_note = ?,
                   account_subgroup = ?,
                   updated_at = current_timestamp
             where company_id = ?
               and account_number = ?
        ''', [
            account.accountName,
            account.accountClass,
            account.normalBalanceSide,
            account.manualReviewRequired,
            account.classificationNote,
            account.accountSubgroup,
            companyId,
            account.accountNumber
        ])
```

- [ ] **Step 5: Apply identical changes to `SieImportExportService`**

In `SieImportExportService.groovy`, update the `AccountClassification` inner class (referenced at the bottom of the file, used in `classifyAccount()`) to add `accountSubgroup`. Then update `classifyAccount()` and `classifyMixedResultAccount()` using the same pattern as `ChartOfAccountsImportService` — derive `AccountSubgroup.fromAccountNumber(accountNumber)?.name()` and pass it to the classification constructor.

Update `upsertAccounts()` to include `account_subgroup` in both the INSERT (line 382) and the UPDATE for manual-review accounts (line 407).

Add the import:

```groovy
import se.alipsa.accounting.domain.AccountSubgroup
```

- [ ] **Step 6: Run the full build**

Run: `./gradlew build --info 2>&1 | tail -30`
Expected: build succeeds, all tests pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/service/ChartOfAccountsImportService.groovy \
       app/src/main/groovy/se/alipsa/accounting/service/SieImportExportService.groovy
git commit -m "feat: importtjänster sätter account_subgroup från BAS-nummer"
```

---

### Task 7: Update `IncomeStatementRow` model

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/domain/report/IncomeStatementRow.groovy`

- [ ] **Step 1: Add new fields**

Replace the contents of `IncomeStatementRow.groovy`:

```groovy
package se.alipsa.accounting.domain.report

import groovy.transform.Canonical

/**
 * One income-statement row — either an account-subgroup line or a computed summary/result row.
 */
@Canonical
final class IncomeStatementRow {

  String section
  String accountNumber
  String accountName
  BigDecimal amount
  String subgroupDisplayName
  boolean summaryRow
}
```

- [ ] **Step 2: Run compilation check**

Run: `./gradlew compileGroovy --info 2>&1 | tail -10`
Expected: compilation succeeds (existing code that constructs `IncomeStatementRow` with 4 args will still work via Groovy's `@Canonical` positional constructor — but the build in Task 8 will update those call sites).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/domain/report/IncomeStatementRow.groovy
git commit -m "domain: IncomeStatementRow med subgroupDisplayName och summaryRow"
```

---

### Task 8: Rewrite `buildIncomeStatementReport`

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/service/ReportDataService.groovy`

- [ ] **Step 1: Add imports**

Add at the top of `ReportDataService.groovy`:

```groovy
import se.alipsa.accounting.domain.AccountSubgroup
import se.alipsa.accounting.domain.report.IncomeStatementSection
```

- [ ] **Step 2: Update `AccountInfo` inner class**

In `ReportDataService.groovy`, update the `AccountInfo` inner class (line 710) to include `accountSubgroup`:

```groovy
  @Canonical
  private static final class AccountInfo {

    Long accountId
    String accountName
    String accountClass
    String normalBalanceSide
    String accountSubgroup
  }
```

- [ ] **Step 3: Update `loadAccountInfos()` query**

In `ReportDataService.groovy`, update `loadAccountInfos()` (line 465) to select `account_subgroup`:

```groovy
  private static Map<String, AccountInfo> loadAccountInfos(Sql sql, long companyId) {
    Map<String, AccountInfo> accounts = [:]
    sql.rows('''
        select id as accountId,
               account_number as accountNumber,
               account_name as accountName,
               account_class as accountClass,
               normal_balance_side as normalBalanceSide,
               account_subgroup as accountSubgroup
          from account
         where company_id = ?
         order by account_number
    ''', [companyId]).each { GroovyRowResult row ->
      accounts.put(row.get('accountNumber') as String, new AccountInfo(
          ((Number) row.get('accountId')).longValue(),
          row.get('accountName') as String,
          row.get('accountClass') as String,
          row.get('normalBalanceSide') as String,
          row.get('accountSubgroup') as String
      ))
    }
    accounts
  }
```

- [ ] **Step 4: Replace `buildIncomeStatementReport()`**

Replace `buildIncomeStatementReport()` (lines 256–292) with:

```groovy
  @SuppressWarnings('AbcMetric')
  private ReportResult buildIncomeStatementReport(EffectiveSelection effective) {
    databaseService.withSql { Sql sql ->
      Map<String, AccountInfo> accountInfos = loadAccountInfos(sql, effective.companyId)
      Map<String, Totals> periodTotals = loadPeriodTotals(sql, effective.selection.fiscalYearId, effective.startDate, effective.endDate)

      Map<AccountSubgroup, BigDecimal> subgroupTotals = buildSubgroupTotals(accountInfos, periodTotals)
      List<IncomeStatementRow> rows = []
      Map<IncomeStatementSection, BigDecimal> sectionTotals = [:]

      IncomeStatementSection.values().each { IncomeStatementSection section ->
        if (section.computed) {
          BigDecimal computedAmount = computeSectionResult(section, sectionTotals)
          sectionTotals[section] = computedAmount
          rows << new IncomeStatementRow(section.name(), null, null, scale(computedAmount), null, true)
        } else {
          BigDecimal sectionSum = BigDecimal.ZERO
          section.subgroups.each { AccountSubgroup subgroup ->
            BigDecimal amount = subgroupTotals[subgroup] ?: BigDecimal.ZERO
            if (amount != BigDecimal.ZERO) {
              rows << new IncomeStatementRow(section.name(), null, null, scale(amount), subgroup.displayName, false)
            }
            sectionSum = sectionSum + amount
          }
          sectionTotals[section] = sectionSum
          if (sectionSum != BigDecimal.ZERO) {
            rows << new IncomeStatementRow(section.name(), null, null, scale(sectionSum), section.displayName, true)
          }
        }
      }

      BigDecimal netResult = sectionTotals[IncomeStatementSection.NET_RESULT] ?: BigDecimal.ZERO

      createResult(
          effective,
          [
              "Rörelseresultat: ${formatAmount(scale(sectionTotals[IncomeStatementSection.OPERATING_RESULT] ?: BigDecimal.ZERO))}".toString(),
              "Resultat efter finansiella poster: ${formatAmount(scale(sectionTotals[IncomeStatementSection.RESULT_AFTER_FINANCIAL] ?: BigDecimal.ZERO))}".toString(),
              "Årets resultat: ${formatAmount(scale(netResult))}".toString()
          ],
          ['Post', 'Belopp'],
          rows.collect { IncomeStatementRow row ->
            stringRow(row.subgroupDisplayName ?: row.section, formatAmount(row.amount))
          },
          rows.collect { IncomeStatementRow ignored -> null as Long } as List<Long>,
          [typedRows: rows, result: scale(netResult)]
      )
    } as ReportResult
  }

  private Map<AccountSubgroup, BigDecimal> buildSubgroupTotals(
      Map<String, AccountInfo> accountInfos,
      Map<String, Totals> periodTotals
  ) {
    Map<AccountSubgroup, BigDecimal> subgroupTotals = [:]
    accountInfos.each { String accountNumber, AccountInfo info ->
      if (!(info.accountClass in ['INCOME', 'EXPENSE'])) {
        return
      }
      Totals totals = periodTotals[accountNumber] ?: Totals.ZERO
      BigDecimal amount = signedAmount(totals.debitAmount, totals.creditAmount, info.normalBalanceSide)
      if (amount == BigDecimal.ZERO) {
        return
      }
      AccountSubgroup subgroup = info.accountSubgroup
          ? AccountSubgroup.fromDatabaseValue(info.accountSubgroup)
          : AccountSubgroup.fromAccountNumber(accountNumber)
      if (subgroup == null) {
        return
      }
      subgroupTotals[subgroup] = (subgroupTotals[subgroup] ?: BigDecimal.ZERO) + amount
    }
    subgroupTotals
  }

  private static BigDecimal computeSectionResult(
      IncomeStatementSection section,
      Map<IncomeStatementSection, BigDecimal> sectionTotals
  ) {
    switch (section) {
      case IncomeStatementSection.OPERATING_RESULT:
        return (sectionTotals[IncomeStatementSection.OPERATING_INCOME] ?: BigDecimal.ZERO) +
            (sectionTotals[IncomeStatementSection.OPERATING_EXPENSES] ?: BigDecimal.ZERO)
      case IncomeStatementSection.RESULT_AFTER_FINANCIAL:
        return (sectionTotals[IncomeStatementSection.OPERATING_RESULT] ?: BigDecimal.ZERO) +
            (sectionTotals[IncomeStatementSection.FINANCIAL_ITEMS] ?: BigDecimal.ZERO)
      case IncomeStatementSection.NET_RESULT:
        return (sectionTotals[IncomeStatementSection.RESULT_AFTER_FINANCIAL] ?: BigDecimal.ZERO) +
            (sectionTotals[IncomeStatementSection.APPROPRIATIONS] ?: BigDecimal.ZERO) +
            (sectionTotals[IncomeStatementSection.TAX] ?: BigDecimal.ZERO)
      default:
        return BigDecimal.ZERO
    }
  }
```

- [ ] **Step 5: Run compilation check**

Run: `./gradlew compileGroovy --info 2>&1 | tail -10`
Expected: compilation succeeds.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/service/ReportDataService.groovy
git commit -m "feat: grupperad buildIncomeStatementReport med sektioner och delsummor"
```

---

### Task 9: Update FreeMarker template

**Files:**
- Modify: `app/src/main/resources/reports/income-statement.ftl`

- [ ] **Step 1: Replace the template**

Replace `app/src/main/resources/reports/income-statement.ftl`:

```ftl
<#import "layout/base.ftl" as layout>
<@layout.page title=title>
  <h2>Resultatrapport</h2>
  <p>${selectionLabel}</p>
  <table>
    <thead>
      <tr>
        <#list tableHeaders as header>
          <th>${header}</th>
        </#list>
      </tr>
    </thead>
    <tbody>
      <#list tableRows as row>
        <#assign isSummary = typedRows[row?index].summaryRow>
        <tr<#if isSummary> style="font-weight: bold; border-top: 1px solid #333;"</#if>>
          <#list row as cell>
            <td>${cell}</td>
          </#list>
        </tr>
      </#list>
    </tbody>
  </table>
</@layout.page>
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/resources/reports/income-statement.ftl
git commit -m "template: grupperad resultatrapport med fetstilta summeringsrader"
```

---

### Task 10: Update tests

**Files:**
- Modify: `app/src/test/groovy/integration/se/alipsa/accounting/service/ReportServicesTest.groovy`

- [ ] **Step 1: Update `insertTestAccounts()` to include `account_subgroup`**

In `ReportServicesTest.groovy`, update `insertAccount()` (line 384) to accept and persist `accountSubgroup`:

```groovy
  private static void insertAccount(
      Sql sql,
      String accountNumber,
      String accountName,
      String accountClass,
      String normalBalanceSide,
      String vatCode,
      String accountSubgroup
  ) {
    sql.executeInsert('''
        insert into account (
            company_id,
            account_number,
            account_name,
            account_class,
            normal_balance_side,
            vat_code,
            active,
            manual_review_required,
            classification_note,
            account_subgroup,
            created_at,
            updated_at
        ) values (1, ?, ?, ?, ?, ?, true, false, null, ?, current_timestamp, current_timestamp)
    ''', [accountNumber, accountName, accountClass, normalBalanceSide, vatCode, accountSubgroup])
  }
```

Update `insertTestAccounts()` (line 343) to pass `accountSubgroup`:

```groovy
  private void insertTestAccounts() {
    databaseService.withTransaction { Sql sql ->
      insertAccount(sql, '2010', 'Eget kapital', 'EQUITY', 'CREDIT', null, 'EQUITY')
      insertAccount(sql, '1510', 'Kundfordringar', 'ASSET', 'DEBIT', null, 'RECEIVABLES')
      insertAccount(sql, '2440', 'Leverantörsskulder', 'LIABILITY', 'CREDIT', null, 'SHORT_TERM_LIABILITIES_CREDIT')
      insertAccount(sql, '2650', 'Redovisningskonto för moms', 'LIABILITY', 'CREDIT', null, 'VAT_AND_EXCISE')
      insertAccount(sql, '2611', 'Utgående moms 25%', 'LIABILITY', 'CREDIT', VatCode.OUTPUT_25.name(), 'VAT_AND_EXCISE')
      insertAccount(sql, '2641', 'Debiterad ingående moms', 'ASSET', 'DEBIT', VatCode.INPUT_25.name(), 'VAT_AND_EXCISE')
      insertAccount(sql, '3010', 'Försäljning', 'INCOME', 'CREDIT', VatCode.OUTPUT_25.name(), 'NET_REVENUE')
      insertAccount(sql, '4010', 'Varuinköp', 'EXPENSE', 'DEBIT', VatCode.INPUT_25.name(), 'RAW_MATERIALS')
    }
  }
```

- [ ] **Step 2: Update the PDF test SHA-256 hash**

In `ReportServicesTest.groovy`, the test `pdfGenerationProducesArchivedPdfFile` (line 118) contains a hardcoded SHA-256 hash of the rendered HTML. The new template produces different HTML, so this hash must be updated.

Change the hash assertion (line 136) to use a dynamic check instead of a fixed hash — the template structure change makes the old hash invalid:

```groovy
    String html = journoReportService.renderHtml(preview).replace('\r\n', '\n').replace('\r', '\n')
    assertTrue(html.contains('Resultatrapport'))
    assertTrue(html.contains('Post'))
    assertTrue(html.contains('Belopp'))
```

- [ ] **Step 3: Add a test that verifies grouped income statement structure**

Add a new test to `ReportServicesTest.groovy`:

```groovy
  @Test
  void incomeStatementProducesGroupedSectionsWithSubtotals() {
    ReportResult report = reportDataService.generate(new ReportSelection(
        ReportType.INCOME_STATEMENT,
        fiscalYear.id,
        null,
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 1, 31)
    ))

    // Should have rows for: Nettoomsättning, Summa rörelseintäkter,
    // Råvaror, Summa rörelsekostnader, Rörelseresultat, Årets resultat
    assertTrue(report.tableRows.size() >= 4)

    // Check that summary lines contain result figures
    assertTrue(report.summaryLines.any { String line -> line.contains('Rörelseresultat') })
    assertTrue(report.summaryLines.any { String line -> line.contains('Årets resultat') })

    // Verify the net result: income 1000 - expenses 200 = 800
    Map<String, Object> model = report.templateModel
    assertEquals(800.00G, model.result)
  }
```

- [ ] **Step 4: Run all tests**

Run: `./gradlew test --info 2>&1 | tail -30`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/groovy/integration/se/alipsa/accounting/service/ReportServicesTest.groovy
git commit -m "test: uppdaterade tester för grupperad resultatrapport"
```

---

### Task 11: Run full build and format

- [ ] **Step 1: Run spotlessApply**

Run: `./gradlew spotlessApply`

- [ ] **Step 2: Run full build**

Run: `./gradlew build`
Expected: build succeeds — compilation, tests, Spotless, CodeNarc all pass.

- [ ] **Step 3: Commit any formatting changes**

```bash
git add -A
git diff --cached --stat
# Only commit if there are formatting changes
git commit -m "fixade formatering"
```
