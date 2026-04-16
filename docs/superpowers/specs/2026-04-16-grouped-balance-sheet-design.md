# Grouped Balance Sheet Design

## Goal

Replace the flat balance sheet report with a properly grouped report following Swedish accounting standards (ÅRL/K2/K3) based on BAS account subgroups. The balance sheet should show a three-level hierarchy: **Section → Subgroup → Individual accounts**, with subtotals at subgroup and section levels, and computed total rows for SUMMA TILLGÅNGAR and SUMMA EGET KAPITAL OCH SKULDER.

## Background

The income statement was recently refactored (PR #27) from a flat two-section layout to a grouped report using `IncomeStatementSection` → `AccountSubgroup` mapping. The balance sheet has the same issues the income statement had before that refactoring:

- Hardcoded Swedish section names in service code and template
- Flat structure — individual accounts listed under 3 broad sections (Tillgångar/Skulder/Eget kapital)
- No `AccountSubgroup` usage despite the enum already covering balance-account subgroups (10-29)
- No subtotals for subgroups
- Column headers hardcoded

## Architecture

### `BalanceSheetSection` enum

New enum in `se.alipsa.accounting.domain.report`, following the same pattern as `IncomeStatementSection`. Each section maps to a list of `AccountSubgroup` values. Computed sections (totals) have an empty subgroup list.

| Section                        | AccountSubgroups                                                                                             | Computed? |
|--------------------------------|--------------------------------------------------------------------------------------------------------------|-----------|
| `FIXED_ASSETS`                 | INTANGIBLE_ASSETS, BUILDINGS_AND_LAND, MACHINERY, FINANCIAL_FIXED_ASSETS                                     | no        |
| `CURRENT_ASSETS`               | INVENTORY, RECEIVABLES, OTHER_CURRENT_RECEIVABLES, PREPAID_EXPENSES, SHORT_TERM_INVESTMENTS, CASH_AND_BANK   | no        |
| `TOTAL_ASSETS`                 | —                                                                                                            | yes       |
| `EQUITY`                       | EQUITY                                                                                                       | no        |
| `UNTAXED_RESERVES`             | UNTAXED_RESERVES                                                                                             | no        |
| `PROVISIONS`                   | PROVISIONS                                                                                                   | no        |
| `LONG_TERM_LIABILITIES`        | LONG_TERM_LIABILITIES                                                                                        | no        |
| `CURRENT_LIABILITIES`          | SHORT_TERM_LIABILITIES_CREDIT, TAX_LIABILITIES, VAT_AND_EXCISE, PAYROLL_TAXES, OTHER_CURRENT_LIABILITIES, ACCRUED_EXPENSES | no |
| `TOTAL_EQUITY_AND_LIABILITIES` | —                                                                                                            | yes       |

**Computation formulas:**
- `TOTAL_ASSETS` = `FIXED_ASSETS` + `CURRENT_ASSETS`
- `TOTAL_EQUITY_AND_LIABILITIES` = `EQUITY` + `UNTAXED_RESERVES` + `PROVISIONS` + `LONG_TERM_LIABILITIES` + `CURRENT_LIABILITIES`

### `BalanceSheetRow` (rewritten)

Replace the current 4-field class with a 6-field class supporting three row kinds:

```
String section
String accountNumber       — null for subgroup subtotal and computed rows
String accountName         — null for subgroup subtotal and computed rows
BigDecimal amount
String subgroupDisplayName — null for account-detail and computed rows
boolean summaryRow         — true for subgroup subtotals and computed totals
```

**Row kinds:**
1. **Account detail row**: `accountNumber` + `accountName` populated, `subgroupDisplayName` = null, `summaryRow` = false
2. **Subgroup subtotal**: `subgroupDisplayName` populated, `accountNumber`/`accountName` = null, `summaryRow` = true
3. **Section subtotal / computed total**: `subgroupDisplayName` = null, section display name used as label, `summaryRow` = true

### `buildBalanceSheetReport` rewrite

The method in `ReportDataService` will be rewritten following the income statement pattern but with account-level detail:

1. Load account infos and compute closing balances (opening + movements) — same as today.
2. Build two maps:
   - `Map<AccountSubgroup, BigDecimal>` — subgroup totals for subtotal rows
   - `Map<AccountSubgroup, List<AccountDetail>>` — per-account detail within each subgroup (account number, name, amount)
3. Iterate `BalanceSheetSection.values()` in declaration order:
   - **Computed section**: calculate from `sectionTotals` map, emit one summary row.
   - **Data section**: for each subgroup in the section:
     - Emit individual account detail rows (sorted by account number)
     - Emit subgroup subtotal row (if more than one account or if the subgroup total is non-zero)
     - Accumulate into section sum
   - Emit section subtotal row
4. Log warning for ASSET/LIABILITY/EQUITY accounts with null subgroup (same pattern as income statement).

**Summary lines** (displayed in UI preview):
- `TOTAL_ASSETS.displayName`: formatted total
- `TOTAL_EQUITY_AND_LIABILITIES.displayName`: formatted total

**Column headers** via i18n keys (same pattern as income statement).

**Template model extras**: `typedRows` (for template styling), `assetTotal`, `equityAndLiabilitiesTotal`.

### Template update (`balance-sheet.ftl`)

- Remove the hardcoded metrics `<div>` block (replace with `<p>${selectionLabel}</p>` like income statement)
- Use `typedRows[row?index].summaryRow` to apply bold + border styling on subtotal/total rows
- Column count changes from 4 to 3: Post (item name), Konto (account number, blank for subtotals), Belopp (amount). Or alternatively keep a simpler 2-column layout like the income statement — but since we show individual accounts, 3 columns (Post/Konto, Namn, Belopp) makes more sense. Actually, to keep it clean: the display name column combines subgroup name or account number+name, so we can use the same 2-column layout as income statement (Post, Belopp) with account detail rows showing "  1220 Maskiner och inventarier" as the Post value.

**Decision: 2-column layout** (`Post`, `Belopp`) matching the income statement. Account detail rows show `"accountNumber accountName"` as the post value. Subgroup and section subtotals show the display name. This keeps the two reports consistent.

### i18n

New keys in both `messages.properties` and `messages_sv.properties`:

```properties
# BalanceSheetSection
balanceSheetSection.FIXED_ASSETS=Fixed assets / Anläggningstillgångar
balanceSheetSection.CURRENT_ASSETS=Current assets / Omsättningstillgångar
balanceSheetSection.TOTAL_ASSETS=Total assets / Summa tillgångar
balanceSheetSection.EQUITY=Equity / Eget kapital
balanceSheetSection.UNTAXED_RESERVES=Untaxed reserves / Obeskattade reserver
balanceSheetSection.PROVISIONS=Provisions / Avsättningar
balanceSheetSection.LONG_TERM_LIABILITIES=Long-term liabilities / Långfristiga skulder
balanceSheetSection.CURRENT_LIABILITIES=Current liabilities / Kortfristiga skulder
balanceSheetSection.TOTAL_EQUITY_AND_LIABILITIES=Total equity and liabilities / Summa eget kapital och skulder
balanceSheetSection.column.item=Item / Post
balanceSheetSection.column.amount=Amount / Belopp
```

(The AccountSubgroup display names already exist from PR #27.)

### `computeSectionResult` for balance sheet

A new private method in `ReportDataService` (or a method on the enum itself) with a switch:

```
TOTAL_ASSETS = FIXED_ASSETS + CURRENT_ASSETS
TOTAL_EQUITY_AND_LIABILITIES = EQUITY + UNTAXED_RESERVES + PROVISIONS + LONG_TERM_LIABILITIES + CURRENT_LIABILITIES
default → throw IllegalStateException
```

## Testing

- **Unit test**: `BalanceSheetSectionTest` — verify subgroup-to-section mapping, `isComputed()` predicate.
- **Integration test**: `ReportServicesTest` — new test `balanceSheetProducesGroupedSectionsWithSubtotals`:
  - Use existing test fixtures (accounts 1510, 2010, 2440, etc. already have `accountSubgroup` set)
  - Verify sections present, subtotals correct, TOTAL_ASSETS == TOTAL_EQUITY_AND_LIABILITIES
  - Verify individual account rows appear within their subgroups

## Files changed

- **New**: `BalanceSheetSection.groovy` (domain/report)
- **Modified**: `BalanceSheetRow.groovy` (add fields)
- **Modified**: `ReportDataService.groovy` (rewrite `buildBalanceSheetReport`)
- **Modified**: `balance-sheet.ftl` (grouped layout with conditional styling)
- **Modified**: `messages.properties` + `messages_sv.properties` (new i18n keys)
- **Modified**: `ReportServicesTest.groovy` (new/updated balance sheet test)
- **New** (optional): `BalanceSheetSectionTest.groovy` (unit test for enum)

## Out of scope

- Individual account drill-down or clickable rows
- Balance sheet comparison (current period vs previous period)
- Schema changes — the `account_subgroup` column already exists from V18
