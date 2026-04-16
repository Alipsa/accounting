# Grouped Balance Sheet Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the flat balance sheet with a grouped report following ÅRL/K2/K3, showing Section → Subgroup → Individual accounts with subtotals at each level.

**Architecture:** A new `BalanceSheetSection` enum maps `AccountSubgroup` values to ÅRL sections (fixed assets, current assets, equity, liabilities) with computed total rows. `BalanceSheetRow` gains `subgroupDisplayName` and `summaryRow` fields while keeping `accountNumber`/`accountName` for detail rows. `buildBalanceSheetReport` is rewritten to produce three-level grouped output.

**Tech Stack:** Groovy 5, JUnit 6, FreeMarker templates, I18n properties

**Spec:** `docs/superpowers/specs/2026-04-16-grouped-balance-sheet-design.md`

---

### Task 1: Create `BalanceSheetSection` enum

**Files:**
- Create: `app/src/main/groovy/se/alipsa/accounting/domain/report/BalanceSheetSection.groovy`
- Create: `app/src/test/groovy/unit/se/alipsa/accounting/domain/report/BalanceSheetSectionTest.groovy`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/groovy/unit/se/alipsa/accounting/domain/report/BalanceSheetSectionTest.groovy`:

```groovy
package se.alipsa.accounting.domain.report

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

import se.alipsa.accounting.domain.AccountSubgroup
import org.junit.jupiter.api.Test

class BalanceSheetSectionTest {

  @Test
  void fixedAssetsContainsExpectedSubgroups() {
    assertEquals(
        [AccountSubgroup.INTANGIBLE_ASSETS, AccountSubgroup.BUILDINGS_AND_LAND,
         AccountSubgroup.MACHINERY, AccountSubgroup.FINANCIAL_FIXED_ASSETS],
        BalanceSheetSection.FIXED_ASSETS.subgroups
    )
    assertFalse(BalanceSheetSection.FIXED_ASSETS.computed)
  }

  @Test
  void currentAssetsContainsExpectedSubgroups() {
    assertEquals(
        [AccountSubgroup.INVENTORY, AccountSubgroup.RECEIVABLES,
         AccountSubgroup.OTHER_CURRENT_RECEIVABLES, AccountSubgroup.PREPAID_EXPENSES,
         AccountSubgroup.SHORT_TERM_INVESTMENTS, AccountSubgroup.CASH_AND_BANK],
        BalanceSheetSection.CURRENT_ASSETS.subgroups
    )
    assertFalse(BalanceSheetSection.CURRENT_ASSETS.computed)
  }

  @Test
  void totalAssetsIsComputed() {
    assertTrue(BalanceSheetSection.TOTAL_ASSETS.computed)
    assertTrue(BalanceSheetSection.TOTAL_ASSETS.subgroups.isEmpty())
  }

  @Test
  void currentLiabilitiesContainsExpectedSubgroups() {
    assertEquals(
        [AccountSubgroup.SHORT_TERM_LIABILITIES_CREDIT, AccountSubgroup.TAX_LIABILITIES,
         AccountSubgroup.VAT_AND_EXCISE, AccountSubgroup.PAYROLL_TAXES,
         AccountSubgroup.OTHER_CURRENT_LIABILITIES, AccountSubgroup.ACCRUED_EXPENSES],
        BalanceSheetSection.CURRENT_LIABILITIES.subgroups
    )
  }

  @Test
  void totalEquityAndLiabilitiesIsComputed() {
    assertTrue(BalanceSheetSection.TOTAL_EQUITY_AND_LIABILITIES.computed)
  }

  @Test
  void everyBalanceAccountSubgroupAppearsInExactlyOneSection() {
    List<AccountSubgroup> balanceSubgroups = AccountSubgroup.values().findAll { AccountSubgroup sg ->
      sg.basGroupStart >= 10 && sg.basGroupEnd <= 29
    }
    List<AccountSubgroup> allMapped = BalanceSheetSection.values()
        .collectMany { BalanceSheetSection section -> section.subgroups }
    balanceSubgroups.each { AccountSubgroup sg ->
      assertEquals(1, allMapped.count { AccountSubgroup mapped -> mapped == sg },
          "AccountSubgroup ${sg.name()} should appear in exactly one section")
    }
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'unit.se.alipsa.accounting.domain.report.BalanceSheetSectionTest' --info 2>&1 | tail -20`
Expected: compilation failure — `BalanceSheetSection` does not exist yet.

- [ ] **Step 3: Write the enum**

Create `app/src/main/groovy/se/alipsa/accounting/domain/report/BalanceSheetSection.groovy`:

```groovy
package se.alipsa.accounting.domain.report

import se.alipsa.accounting.domain.AccountSubgroup
import se.alipsa.accounting.support.I18n

/**
 * Logical sections of the balance sheet report, each mapping to one or more
 * {@link AccountSubgroup} values. Sections with an empty subgroup list are computed
 * total rows.
 */
enum BalanceSheetSection {

  FIXED_ASSETS([
      AccountSubgroup.INTANGIBLE_ASSETS,
      AccountSubgroup.BUILDINGS_AND_LAND,
      AccountSubgroup.MACHINERY,
      AccountSubgroup.FINANCIAL_FIXED_ASSETS
  ]),
  CURRENT_ASSETS([
      AccountSubgroup.INVENTORY,
      AccountSubgroup.RECEIVABLES,
      AccountSubgroup.OTHER_CURRENT_RECEIVABLES,
      AccountSubgroup.PREPAID_EXPENSES,
      AccountSubgroup.SHORT_TERM_INVESTMENTS,
      AccountSubgroup.CASH_AND_BANK
  ]),
  TOTAL_ASSETS([]),
  EQUITY([
      AccountSubgroup.EQUITY
  ]),
  UNTAXED_RESERVES([
      AccountSubgroup.UNTAXED_RESERVES
  ]),
  PROVISIONS([
      AccountSubgroup.PROVISIONS
  ]),
  LONG_TERM_LIABILITIES([
      AccountSubgroup.LONG_TERM_LIABILITIES
  ]),
  CURRENT_LIABILITIES([
      AccountSubgroup.SHORT_TERM_LIABILITIES_CREDIT,
      AccountSubgroup.TAX_LIABILITIES,
      AccountSubgroup.VAT_AND_EXCISE,
      AccountSubgroup.PAYROLL_TAXES,
      AccountSubgroup.OTHER_CURRENT_LIABILITIES,
      AccountSubgroup.ACCRUED_EXPENSES
  ]),
  TOTAL_EQUITY_AND_LIABILITIES([])

  final List<AccountSubgroup> subgroups

  BalanceSheetSection(List<AccountSubgroup> subgroups) {
    this.subgroups = Collections.unmodifiableList(subgroups)
  }

  boolean isComputed() {
    subgroups.isEmpty()
  }

  String getDisplayName() {
    I18n.instance.getString("balanceSheetSection.${name()}")
  }

  @Override
  String toString() {
    displayName
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'unit.se.alipsa.accounting.domain.report.BalanceSheetSectionTest' --info 2>&1 | tail -20`
Expected: all 6 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/domain/report/BalanceSheetSection.groovy \
       app/src/test/groovy/unit/se/alipsa/accounting/domain/report/BalanceSheetSectionTest.groovy
git commit -m "feat: BalanceSheetSection enum med ÅRL-sektioner"
```

---

### Task 2: Add i18n keys for `BalanceSheetSection`

**Files:**
- Modify: `app/src/main/resources/i18n/messages.properties`
- Modify: `app/src/main/resources/i18n/messages_sv.properties`

- [ ] **Step 1: Add English i18n keys**

Add the following block after the `# IncomeStatementSection` block in `app/src/main/resources/i18n/messages.properties`:

```properties
# BalanceSheetSection
balanceSheetSection.FIXED_ASSETS=Fixed assets
balanceSheetSection.CURRENT_ASSETS=Current assets
balanceSheetSection.TOTAL_ASSETS=Total assets
balanceSheetSection.EQUITY=Equity
balanceSheetSection.UNTAXED_RESERVES=Untaxed reserves
balanceSheetSection.PROVISIONS=Provisions
balanceSheetSection.LONG_TERM_LIABILITIES=Long-term liabilities
balanceSheetSection.CURRENT_LIABILITIES=Current liabilities
balanceSheetSection.TOTAL_EQUITY_AND_LIABILITIES=Total equity and liabilities
balanceSheetSection.column.item=Item
balanceSheetSection.column.amount=Amount
```

- [ ] **Step 2: Add Swedish i18n keys**

Add the following block after the `# IncomeStatementSection` block in `app/src/main/resources/i18n/messages_sv.properties`:

```properties
# BalanceSheetSection
balanceSheetSection.FIXED_ASSETS=Anläggningstillgångar
balanceSheetSection.CURRENT_ASSETS=Omsättningstillgångar
balanceSheetSection.TOTAL_ASSETS=Summa tillgångar
balanceSheetSection.EQUITY=Eget kapital
balanceSheetSection.UNTAXED_RESERVES=Obeskattade reserver
balanceSheetSection.PROVISIONS=Avsättningar
balanceSheetSection.LONG_TERM_LIABILITIES=Långfristiga skulder
balanceSheetSection.CURRENT_LIABILITIES=Kortfristiga skulder
balanceSheetSection.TOTAL_EQUITY_AND_LIABILITIES=Summa eget kapital och skulder
balanceSheetSection.column.item=Post
balanceSheetSection.column.amount=Belopp
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/resources/i18n/messages.properties \
       app/src/main/resources/i18n/messages_sv.properties
git commit -m "i18n: balansrapport-sektioner på svenska och engelska"
```

---

### Task 3: Rewrite `BalanceSheetRow`

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/domain/report/BalanceSheetRow.groovy`

- [ ] **Step 1: Update the class**

Replace the contents of `app/src/main/groovy/se/alipsa/accounting/domain/report/BalanceSheetRow.groovy` with:

```groovy
package se.alipsa.accounting.domain.report

import groovy.transform.Canonical

/**
 * One balance-sheet row — an account detail line, a subgroup subtotal, or a computed total.
 */
@Canonical
final class BalanceSheetRow {

  String section
  String accountNumber
  String accountName
  BigDecimal amount
  String subgroupDisplayName
  boolean summaryRow
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew compileGroovy 2>&1 | tail -10`
Expected: compilation failure in `ReportDataService.groovy` because `buildBalanceSheetReport` constructs `BalanceSheetRow` with the old 4-argument constructor. This is expected — Task 4 will fix it.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/domain/report/BalanceSheetRow.groovy
git commit -m "domain: BalanceSheetRow med subgroupDisplayName och summaryRow"
```

---

### Task 4: Rewrite `buildBalanceSheetReport` in `ReportDataService`

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/service/ReportDataService.groovy`

- [ ] **Step 1: Add import**

Add this import to the top of `ReportDataService.groovy` (alongside the existing `IncomeStatementSection` import):

```groovy
import se.alipsa.accounting.domain.report.BalanceSheetSection
```

- [ ] **Step 2: Replace `buildBalanceSheetReport` method**

Replace the entire `buildBalanceSheetReport` method (lines 360-409) with:

```groovy
  @SuppressWarnings('AbcMetric')
  private ReportResult buildBalanceSheetReport(EffectiveSelection effective) {
    databaseService.withSql { Sql sql ->
      Map<String, AccountInfo> accountInfos = loadAccountInfos(sql, effective.companyId)
      Map<String, BigDecimal> openingBalances = loadOpeningBalances(sql, effective.selection.fiscalYearId)
      Map<String, BigDecimal> movements = loadSignedMovements(sql, effective.selection.fiscalYearId, effective.fiscalYear.startDate, effective.endDate)

      Map<String, BigDecimal> closingBalances = buildClosingBalances(accountInfos, openingBalances, movements)
      Map<AccountSubgroup, List<AccountDetail>> subgroupAccounts = buildSubgroupAccounts(accountInfos, closingBalances)
      Map<AccountSubgroup, BigDecimal> subgroupTotals = subgroupAccounts.collectEntries { AccountSubgroup sg, List<AccountDetail> details ->
        [(sg): details.sum(BigDecimal.ZERO) { AccountDetail d -> d.amount } as BigDecimal]
      } as Map<AccountSubgroup, BigDecimal>

      List<BalanceSheetRow> rows = []
      Map<BalanceSheetSection, BigDecimal> sectionTotals = [:]

      BalanceSheetSection.values().each { BalanceSheetSection section ->
        if (section.computed) {
          BigDecimal computedAmount = computeBalanceSheetTotal(section, sectionTotals)
          sectionTotals[section] = computedAmount
          rows << new BalanceSheetRow(section.name(), null, null, scale(computedAmount), null, true)
        } else {
          BigDecimal sectionSum = BigDecimal.ZERO
          section.subgroups.each { AccountSubgroup subgroup ->
            List<AccountDetail> accounts = subgroupAccounts[subgroup] ?: []
            BigDecimal sgTotal = subgroupTotals[subgroup] ?: BigDecimal.ZERO
            accounts.each { AccountDetail detail ->
              rows << new BalanceSheetRow(section.name(), detail.accountNumber, detail.accountName, scale(detail.amount), null, false)
            }
            if (accounts.size() > 0 && sgTotal != BigDecimal.ZERO) {
              rows << new BalanceSheetRow(section.name(), null, null, scale(sgTotal), subgroup.displayName, true)
            }
            sectionSum = sectionSum + sgTotal
          }
          sectionTotals[section] = sectionSum
          if (sectionSum != BigDecimal.ZERO) {
            rows << new BalanceSheetRow(section.name(), null, null, scale(sectionSum), section.displayName, true)
          }
        }
      }

      BigDecimal assetTotal = sectionTotals[BalanceSheetSection.TOTAL_ASSETS] ?: BigDecimal.ZERO
      BigDecimal equityAndLiabilitiesTotal = sectionTotals[BalanceSheetSection.TOTAL_EQUITY_AND_LIABILITIES] ?: BigDecimal.ZERO

      createResult(
          effective,
          [
              "${BalanceSheetSection.TOTAL_ASSETS.displayName}: ${formatAmount(scale(assetTotal))}".toString(),
              "${BalanceSheetSection.TOTAL_EQUITY_AND_LIABILITIES.displayName}: ${formatAmount(scale(equityAndLiabilitiesTotal))}".toString()
          ],
          [I18n.instance.getString('balanceSheetSection.column.item'), I18n.instance.getString('balanceSheetSection.column.amount')],
          rows.collect { BalanceSheetRow row ->
            String label = row.accountNumber
                ? "${row.accountNumber} ${row.accountName}"
                : (row.subgroupDisplayName ?: row.section)
            stringRow(label, formatAmount(row.amount))
          },
          rows.collect { BalanceSheetRow ignored -> null as Long } as List<Long>,
          [typedRows: rows, assetTotal: scale(assetTotal), equityAndLiabilitiesTotal: scale(equityAndLiabilitiesTotal)]
      )
    } as ReportResult
  }
```

- [ ] **Step 3: Add helper methods**

Add these three private methods right after `buildBalanceSheetReport`, before `buildTransactionReport`:

```groovy
  private Map<String, BigDecimal> buildClosingBalances(
      Map<String, AccountInfo> accountInfos,
      Map<String, BigDecimal> openingBalances,
      Map<String, BigDecimal> movements
  ) {
    Map<String, BigDecimal> closingBalances = [:]
    accountInfos.each { String accountNumber, AccountInfo info ->
      if (!(info.accountClass in ['ASSET', 'LIABILITY', 'EQUITY'])) {
        return
      }
      BigDecimal amount = (openingBalances[accountNumber] ?: BigDecimal.ZERO) + (movements[accountNumber] ?: BigDecimal.ZERO)
      if (amount != BigDecimal.ZERO) {
        closingBalances[accountNumber] = amount
      }
    }
    closingBalances
  }

  private Map<AccountSubgroup, List<AccountDetail>> buildSubgroupAccounts(
      Map<String, AccountInfo> accountInfos,
      Map<String, BigDecimal> closingBalances
  ) {
    Map<AccountSubgroup, List<AccountDetail>> subgroupAccounts = [:]
    closingBalances.keySet().sort().each { String accountNumber ->
      AccountInfo info = accountInfos[accountNumber]
      AccountSubgroup subgroup = info.accountSubgroup
          ? AccountSubgroup.fromDatabaseValue(info.accountSubgroup)
          : AccountSubgroup.fromAccountNumber(accountNumber)
      if (subgroup == null) {
        log.warning("Konto ${accountNumber} (${info.accountName}) saknar undergrupp och exkluderas från balansrapporten.")
        return
      }
      BigDecimal amount = closingBalances[accountNumber]
      subgroupAccounts.computeIfAbsent(subgroup) { [] as List<AccountDetail> }
          .add(new AccountDetail(accountNumber, info.accountName, amount))
    }
    subgroupAccounts
  }

  private static BigDecimal computeBalanceSheetTotal(
      BalanceSheetSection section,
      Map<BalanceSheetSection, BigDecimal> sectionTotals
  ) {
    switch (section) {
      case BalanceSheetSection.TOTAL_ASSETS:
        return (sectionTotals[BalanceSheetSection.FIXED_ASSETS] ?: BigDecimal.ZERO) +
            (sectionTotals[BalanceSheetSection.CURRENT_ASSETS] ?: BigDecimal.ZERO)
      case BalanceSheetSection.TOTAL_EQUITY_AND_LIABILITIES:
        return (sectionTotals[BalanceSheetSection.EQUITY] ?: BigDecimal.ZERO) +
            (sectionTotals[BalanceSheetSection.UNTAXED_RESERVES] ?: BigDecimal.ZERO) +
            (sectionTotals[BalanceSheetSection.PROVISIONS] ?: BigDecimal.ZERO) +
            (sectionTotals[BalanceSheetSection.LONG_TERM_LIABILITIES] ?: BigDecimal.ZERO) +
            (sectionTotals[BalanceSheetSection.CURRENT_LIABILITIES] ?: BigDecimal.ZERO)
      default:
        throw new IllegalStateException("Okänd beräknad sektion: ${section}")
    }
  }
```

- [ ] **Step 4: Add `AccountDetail` inner class**

Add this `@Canonical` class at the bottom of `ReportDataService`, alongside the existing inner classes (`AccountInfo`, `Totals`, etc.):

```groovy
  @Canonical
  private static final class AccountDetail {
    String accountNumber
    String accountName
    BigDecimal amount
  }
```

- [ ] **Step 5: Verify compilation**

Run: `./gradlew compileGroovy 2>&1 | tail -10`
Expected: PASS (compilation succeeds).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/service/ReportDataService.groovy
git commit -m "feat: grupperad buildBalanceSheetReport med sektioner och delsummor"
```

---

### Task 5: Update `balance-sheet.ftl` template

**Files:**
- Modify: `app/src/main/resources/reports/balance-sheet.ftl`

- [ ] **Step 1: Replace the template**

Replace the full contents of `app/src/main/resources/reports/balance-sheet.ftl` with:

```freemarker
<#import "layout/base.ftl" as layout>
<@layout.page title=title>
  <h2>Balansrapport</h2>
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
git add app/src/main/resources/reports/balance-sheet.ftl
git commit -m "template: grupperad balansrapport med villkorlig formatering"
```

---

### Task 6: Update balance sheet integration test

**Files:**
- Modify: `app/src/test/groovy/integration/se/alipsa/accounting/service/ReportServicesTest.groovy`

- [ ] **Step 1: Update `sumSection` helper**

The existing `sumSection` helper assumes a 4-column layout (`row[3]` for amount). The balance sheet now uses a 2-column layout. Replace the `sumSection` method with one that works for both old tests that may still use it and the new format. Actually, check whether any existing tests still call `sumSection` — if none do, remove it. If some do, update the index.

Search for usages first. If `sumSection` is unused after the balance sheet change, delete it.

- [ ] **Step 2: Add the grouped balance sheet test**

Add this test method in `ReportServicesTest`, after the `incomeStatementProducesGroupedSectionsWithSubtotals` test:

```groovy
  @Test
  void balanceSheetProducesGroupedSectionsWithSubtotals() {
    ReportResult report = reportDataService.generate(new ReportSelection(
        ReportType.BALANCE_SHEET,
        fiscalYear.id,
        null,
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 1, 31)
    ))

    // Should have account detail rows + subgroup subtotals + section totals + computed totals
    assertTrue(report.tableRows.size() >= 6)

    // Check that summary lines contain total figures
    assertTrue(report.summaryLines.any { String line -> line.contains('Summa tillgångar') })
    assertTrue(report.summaryLines.any { String line -> line.contains('Summa eget kapital och skulder') })

    // Verify totals balance: assets == equity + liabilities
    Map<String, Object> model = report.templateModel
    BigDecimal assetTotal = model.assetTotal as BigDecimal
    BigDecimal equityAndLiabilitiesTotal = model.equityAndLiabilitiesTotal as BigDecimal
    assertEquals(assetTotal, equityAndLiabilitiesTotal)

    // Verify asset total includes kundfordringar (1510: debit 1250) and ingående moms (2641: debit 50)
    // These are the ASSET accounts from the test fixtures
    assertEquals(1300.00G, assetTotal)
  }
```

- [ ] **Step 3: Run all tests**

Run: `./gradlew test 2>&1 | tail -20`
Expected: all tests PASS, including the new `balanceSheetProducesGroupedSectionsWithSubtotals`.

- [ ] **Step 4: Commit**

```bash
git add app/src/test/groovy/integration/se/alipsa/accounting/service/ReportServicesTest.groovy
git commit -m "test: grupperad balansrapport med sektioner och delsummor"
```

---

### Task 7: Run full build and apply formatting

**Files:**
- All modified files

- [ ] **Step 1: Run spotlessApply**

Run: `./gradlew spotlessApply 2>&1 | tail -10`

- [ ] **Step 2: Run full build**

Run: `./gradlew build 2>&1 | tail -30`
Expected: BUILD SUCCESSFUL — all tests pass, Spotless and CodeNarc clean.

- [ ] **Step 3: Commit any formatting fixes**

If spotlessApply made changes:

```bash
git add -A
git commit -m "fixade formattering"
```

If no changes, skip this step.
