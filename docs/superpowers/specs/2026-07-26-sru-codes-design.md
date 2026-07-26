# SRU Codes for SIE Export — Design Spec

**Date:** 2026-07-26
**Trigger:** 2025 SIE export rejected by tax software's "SRU-kontroll" — accounts 1630, 1930, 2081, 2091, 2099, 2510, 2519, 2650 flagged as missing SRU codes (`Röd = SRU-kod saknas`).

## Context

SRU codes (`#SRU <account> <fältkod>`) map each account to a box/field on the relevant Swedish tax return form (INK2 for aktiebolag, NE for enskild firma, INK4 for handelsbolag/KB). Every account used in a fiscal year needs one for tax software to accept a SIE import. This app has never emitted `#SRU` lines:

- `SieImportExportService.buildExportPayload()` builds each `SieAccount` with only number + name; `setSRU(...)` (supported by the underlying `SieParser` library) is never called.
- No `sru_code` field exists on `Account` or the `account` table.
- No `legal_form` field exists on `Company`, which matters because the same BAS account can map to a different SRU field depending on legal form (e.g. account 2099 "Årets resultat" is a different field on NE vs INK2).

The user supplied the official BAS→SRU mapping spreadsheets (from bas.se/kontoplaner/sru/) into `docs/SRU/`:
`INK2_P1_intervall-241119.xlsx`, `INK4_P1_Intervall-241119.xlsx`, `NE_EJ_K1-Intervall-231002.xlsx`, `NE_K1-201002.xlsx` (the non-"intervall" INK2/INK4 files are redundant — same data with `xx`-wildcard notation instead of expanded ranges, so they're not used as a parse source).

### Validation against a real SIE export

Before finalizing this spec, a real SIE file exported from Björn Lundén's accounting software for the same company (`New_Field_AB-2025.SE`) was diffed against a draft parse of `INK2_P1_intervall-241119.xlsx`. Findings that shaped this spec:

- The `P1` in these filenames is **not** a fiscal-year-end-dependent variant — bas.se explicitly states *"I dagsläget finns inte olika kopplingsscheman baserat på granskningsperioder"* (currently no separate connection schemes exist per Skatteverket review period). One INK2 table applies regardless of fiscal year end; the files already saved are the only/correct ones.
- A draft parser bug was found and fixed: a `(Om netto ±)` sign tag appearing mid-list (e.g. `8810 (Om netto +), 8819`) was truncating everything after it, silently dropping accounts listed later in the same row. Fixed by applying sign tags per comma-segment instead of per whole cell.
- After the fix, the parser reproduced **208 of 210** real `#SRU` lines exactly (excluding 8 lines covered by the next point). The 2 remaining misses (accounts 8710, 8750) appear to be this company's own non-standard account numbers outside official BAS ranges — an inherent limit of any suggestion table, not a parsing defect.
- **7 accounts in the real file carry a second `#SRU` code** (e.g. `6072 → 7513` and `6072 → 7653`) that doesn't exist anywhere in the INK2 main table. These belong to **INK2S (Skattemässiga justeringar)**, a separate attachment form to INK2. BAS's public SRU download page does not offer a downloadable kopplingstabell for INK2S — there is no authoritative bulk mapping available for it, so it is **not** covered by the suggestion feature (see §3). This is why `sru_code2` (§2) exists as a manual-entry field with no suggestion source.

## Scope

- `DatabaseService.MIGRATIONS` — register the two new migrations
- `Company` + `CompanyService` (create/update/mapCompany) — legal form + simplified-annual-report flag, full persistence path
- `Account` + `AccountService` (searchAccounts/findAccount/mapAccount/updateAccount) — sru code fields, full persistence path
- New bundled SRU suggestion data (parsed once from the xlsx files) + lookup service
- `SieImportExportService` — export emits `#SRU` and warns about used-but-uncoded accounts, import persists `#SRU` on new accounts only
- `AccountEditorDialog` / `ChartOfAccountsPanel` (+ its `MainFrame` instantiation) — SRU code fields + suggestion hint, with `ChartOfAccountsPanel` resolving the active `Company` and calling `SruSuggestionService` so the dialog itself stays a plain, stateless view
- `CompanyDialog` — legal form selector

Out of scope: automatic resolution of sign-dependent SRU fields against actual account balances (suggestion shows both candidates, user picks); per-fiscal-year legal form history (legal form lives on `Company`, not versioned); any legal forms beyond AB/enskild firma/handelsbolag-KB; MCP tool surface (can be extended later if needed, not required for this fix); blocking export outright when SRU codes are missing (warn only, see §5a).

---

## 1. Company legal form

`Company.groovy` — new fields appended **at the end** of the field list (after `accountingMethod`), not inserted in the middle, because `CompanyService.mapCompany()` builds `Company` via its `@Canonical` positional all-args constructor; inserting fields anywhere but the end would silently misassign every field after the insertion point:
```groovy
LegalForm legalForm            // nullable — unset until user configures it
boolean simplifiedAnnualReport = false   // only meaningful when legalForm == ENSKILD_FIRMA (K1 vs EJ_K1)
```

New enum `LegalForm { AKTIEBOLAG, ENSKILD_FIRMA, HANDELSBOLAG_KB }` with a null-safe `fromDatabaseValue(String value)`. This must **not** follow the `AccountingMethod.fromDatabaseValue`/`VatPeriodicity.fromDatabaseValue` precedent of `normalized ? valueOf(normalized) : <DEFAULT>` — those fields are `NOT NULL` with a safe default; `legal_form` is deliberately nullable with no safe default (that's the entire reason §1 rejects guessing AKTIEBOLAG for existing companies). So:
```groovy
static LegalForm fromDatabaseValue(String value) {
  String normalized = value?.trim()
  normalized ? valueOf(normalized) : null
}
```

Migration `V28__company_legal_form.sql` (**registered in `DatabaseService.MIGRATIONS` as version 28** — this list is the only thing that applies migrations, for both upgraded and brand-new databases; a new DB bootstraps by running every entry in `MIGRATIONS` from V1, there is no separate full-schema file to update):
```sql
alter table company add column legal_form varchar(20);
alter table company add column simplified_annual_report boolean not null default false;
```

No backfill — existing companies get `legal_form = null` (no suggestions shown) until the user sets it explicitly in company settings. Guessing a default here (e.g. defaulting to AKTIEBOLAG because of the `Aktiekapital` account seen in this instance) would be wrong for other companies in a multi-company install and isn't worth the risk.

**Full persistence path (this is the part that makes the field actually work, not just declared):**
- `CompanyService.create()`: add `legal_form`, `simplified_annual_report` to the `insert into company (...)` column list and value list.
- `CompanyService.update()`: add both columns to the `update company set ...` clause and value list.
- `CompanyService.mapCompany()` is fed by **three separate SELECTs**, all of which need `legal_form as legalForm, simplified_annual_report as simplifiedAnnualReport` added: the private `findById(Sql, long)`, `listCompanies()`, and `listArchivedCompanies()`. Missing any one of them means that view (e.g. the company picker list) silently shows `legalForm == null` even though the value is stored correctly. Append `LegalForm.fromDatabaseValue(row.get('legalForm') as String)` / `Boolean.TRUE == row.get('simplifiedAnnualReport')` as the last two positional constructor arguments in `mapCompany()` itself (matching the appended field order above) — since all three SELECTs funnel through this one mapping method, this part only needs doing once.

Without all three, `legalForm` never round-trips — it'd stay `null` forever regardless of what the user picks in the dialog, and `SruSuggestionService` would never return a suggestion.

Add a legal-form combo box + simplified-annual-report checkbox (enabled only when `ENSKILD_FIRMA` is selected) to `CompanyDialog.groovy`. Its save handler builds `Company` positionally (`Company toSave = new Company(company?.id, companyNameField.text.trim(), ...)`, 11 args currently) — the new combo/checkbox values must be appended as the last two arguments to that same call, or the dialog's selection is purely visual and every save persists `null`/`false` regardless of what the user picked. Test both create and edit through the dialog, not just through `CompanyService` directly.

---

## 2. Per-account SRU code (manual, authoritative)

These are the fields that actually get written to `#SRU` on export, independent of whether a suggestion exists — mirrors the existing `vat_code` pattern.

A real Björn Lundén export confirmed some accounts legitimately need **two** simultaneous SRU codes (main INK2 field + an INK2S tax-adjustment field, see validation note in Context). No observed case needs more than two, so this is modeled as two plain columns rather than a child table:

Migration `V29__account_sru_code.sql` (**registered in `DatabaseService.MIGRATIONS` as version 29**):
```sql
alter table account add column sru_code varchar(10);
alter table account add column sru_code2 varchar(10);
```

`Account.groovy`: add `String sruCode`, `String sruCode2` **at the end** of the field list, for the same positional-constructor reason as `Company` above — `AccountService.mapAccount()` also builds `Account` via its all-args constructor.

`AccountService` gains two shared static helpers, so the format rule is defined once and used by both the manual-edit path and the import path (§6) rather than each reimplementing it:
```groovy
static String normalizeSruCode(String value) {   // trim, empty -> null; no format check
  String trimmed = value?.trim()
  trimmed ?: null
}
static boolean isValidSruCode(String value) {    // null is valid (means "not set"); digits only otherwise
  value == null || value ==~ /\d+/
}
```
- `updateAccount(...)` gains `sruCode`/`sruCode2` parameters. Each is passed through `normalizeSruCode(...)`; if `!isValidSruCode(normalized)`, throw `IllegalArgumentException` — same as today's behavior for a bad manual edit, surfaced directly in the editor dialog.
- `searchAccounts` / `findAccount` SELECT + `mapAccount` include `sru_code as sruCode, sru_code2 as sruCode2`, appended as the last two positional constructor arguments.

`AccountEditorDialog`: add two SRU-code `JTextField`s (primary + secondary), following the existing `addRow(...)` pattern used for `name`/`class`/`normal`/`vatCode`/`active`/`review`. `AccountEditorResult` gains `sruCode`, `sruCode2`.

`ChartOfAccountsPanel`: add "SRU" and "SRU 2" columns to `AccountTableModel`; pass `edited.sruCode`/`edited.sruCode2` through in `editSelectedAccount()`.

i18n: add `chartOfAccountsPanel.table.sruCode`/`.sruCode2` and `chartOfAccountsPanel.edit.description.sruCode`/`.sruCode2` keys (sv + default bundles).

---

## 3. SRU suggestion data + lookup

### Conversion (one-time, not a runtime/build step)

Parse the 4 relevant "intervall" spreadsheets into bundled CSV resources:

```
app/src/main/resources/sru/ink2.csv
app/src/main/resources/sru/ink4.csv
app/src/main/resources/sru/ne_k1.csv
app/src/main/resources/sru/ne_ej_k1.csv
```

Columns: `field_code, account_number, sign_condition, description`
(`sign_condition` ∈ `NONE`, `NET_POSITIVE`, `NET_NEGATIVE`).

Parsing rules applied to the "Konton i BAS ..." column:
- Header/note rows (non-numeric field-code column) are dropped.
- Plain account number → single row.
- Numeric range (`1000-1087`) → expand to individual account numbers.
- Comma-separated lists → union of the above.
- `x`-wildcard tokens (`112x` → 1120-1129, `10xx` → 1000-1099) expanded by replacing trailing `x`s with the full digit range.
- Wildcard-to-wildcard ranges (`40xx-47xx`) expanded as one contiguous range (4000-4799), matching how these account blocks are already grouped contiguously elsewhere in the app (`AccountSubgroup`, `ChartOfAccountsImportService.classifyAccount`).
- `(exkl. NNNN)` → remove that specific account from the expanded set.
- Row-level `(Om netto +)` / `(Om netto -)` suffixes, and `+`/`–` row prefixes → tag every account in that row with the corresponding `sign_condition`.

**Ambiguous cases are excluded, not guessed.** Rows/tokens the parser can't confidently classify — chiefly the per-token mixed-sign lists in NE_EJ_K1 (e.g. `802x(+), 802x(–)` within the same row) — are left out of the generated CSVs and written to `docs/SRU/unparsed.md` instead, so the gap is visible rather than silently wrong.

**Source versioning:** the xlsx filenames already carry BAS's own revision datestamp (e.g. `INK2_P1_intervall-241119.xlsx` = 2024-11-19). Record these filenames plus a sha256 of each in a `docs/SRU/SOURCES.md` manifest, so a future re-generation after BAS publishes an update is a deliberate, reviewable diff (changed hash → re-run conversion → diff the CSV) rather than a silent drift.

**Correctness checks — layered, not just the one end-to-end fixture:**
1. Parser primitives get isolated unit tests independent of any spreadsheet: numeric range expansion, comma-list union, single/double `x`-wildcard expansion, wildcard-to-wildcard range expansion, `(exkl. NNNN)` exclusion, and per-segment `(Om netto ±)` sign tagging (including the mid-list case that was previously broken).
2. Each of the four generated CSVs (`ink2`, `ink4`, `ne_k1`, `ne_ej_k1`) gets an expected-row-count assertion, so a silent change in how many accounts a re-parse produces is caught even without a real-file fixture for INK4/NE.
3. Two separate fixtures/assertions, matching what the real file actually showed (208 mappable, 2 confirmed gaps — a test can't require both "reproduces all 210" and "8710/8750 have no mapping" at once):
   - `app/src/test/resources/sru/ink2-real-export-fixture.csv` holds only the **208** (account_number, sru_code) pairs from the real Björn Lundén export that do match a single INK2 field (no balances/vouchers/company-identifying data, just the pairs). An automated test asserts the parsed `ink2.csv` reproduces all 208. This is what caught the mid-list sign-tag parsing bug during design (see Context).
   - A separate, explicit test asserts `SruSuggestionService.suggest(...)` returns `[]` for accounts `8710` and `8750` under the AKTIEBOLAG table — encoding the known gap as an expectation rather than leaving it as an unstated exception. If a future BAS table update starts covering them, this test starts failing loudly (prompting a deliberate update) instead of silently drifting.
   - No equivalent real-file fixture exists yet for INK4/NE — flagged as a known coverage gap, not silently assumed correct by analogy.

This suggestion table only ever covers the **primary** SRU code (`sru_code`) — the second code some accounts need (`sru_code2`, see §2) comes from INK2S, for which no downloadable BAS table exists, so it has no suggestion source and stays manual-only.

Regenerating these CSVs (e.g. when BAS republishes updated tables next year) is a manual, one-off task — not ongoing build infrastructure, consistent with how the existing BAS chart-of-accounts import is already a manual, user-triggered action rather than an automated sync.

### Lookup service

New `SruSuggestionService`:
```groovy
List<SruSuggestion> suggest(Company company, String accountNumber)
```
- Resolves the CSV by `company.legalForm` (+ `simplifiedAnnualReport` when `ENSKILD_FIRMA`); returns `[]` if `legalForm` is unset.
- Returns all matching rows for the account number. If more than one exists for different sign conditions, all are returned (UI shows both candidates); no automatic resolution against actual account balances in this pass.
- Only ever suggests a value for `sru_code`; never suggests `sru_code2`.

`SruSuggestion`: `fieldCode`, `description`, `signCondition`.

---

## 4. UI: showing suggestions

**Wiring — this is what makes §3's `suggest(...)` reachable from the dialog, not just described in prose.** `AccountEditorDialog` is a static-only, stateless utility today (`private AccountEditorDialog() {}`, no instance fields, no injected services — its one existing service touch, `AccountService.compatibleVatCodes(...)`, is a pure classification function with no DB/state dependency). Giving it a `SruSuggestionService` and a `Company` directly would be the first time this class depends on a stateful collaborator, and would need `ChartOfAccountsPanel` to resolve `activeCompanyManager.activeCompany` and pass it through anyway. Instead, keep the dialog exactly as decoupled as it is today: the caller resolves the suggestions and hands over a plain list.

- `ChartOfAccountsPanel` gains a `SruSuggestionService` constructor parameter (defaulted like its existing collaborators, e.g. `new SruSuggestionService()`), and its instantiation in `MainFrame.groovy` (`new ChartOfAccountsPanel(accountService, chartOfAccountsImportService, activeCompanyManager)`) gets the new argument appended.
- `SruSuggestionService.suggest(Company company, String accountNumber)` is null-safe on `company` — returns `[]` if `company == null` (in addition to the existing `legalForm == null` → `[]` rule from §3), even though in practice `editSelectedAccount()` can only run after `activeCompanyManager.hasActiveCompany()` has already gated the account list (lines 275/300 of `ChartOfAccountsPanel.groovy`), so `activeCompanyManager.activeCompany` will be non-null there.
- `ChartOfAccountsPanel.editSelectedAccount()` computes `List<SruSuggestion> suggestions = sruSuggestionService.suggest(activeCompanyManager.activeCompany, account.accountNumber)` and calls `AccountEditorDialog.show(this, account, suggestions)` — a new third parameter, empty list when there's nothing to suggest. The dialog itself never touches `Company` or `SruSuggestionService`.

In `AccountEditorDialog`, next to the primary SRU code field, show a suggestion hint driven by the passed-in `suggestions` list:
- Single match, no sign condition: `Förslag: 7261 [Använd]` — button fills the text field.
- Sign-dependent matches: both candidates shown with their condition label (e.g. "om nettobelopp +" / "om nettobelopp –"), each with its own `[Använd]`.
- Empty list (legal form unset, or account not covered by the table): no hint shown, field behaves as plain manual entry — same as today.

The secondary SRU code field has no suggestion hint (no source table, see §3) — plain manual entry only.

---

## 5. SIE export

`SieImportExportModels.AccountSeed` gains `String sruCode`, `String sruCode2`.
`SieImportExportService.loadAccounts()` selects `sru_code as sruCode, sru_code2 as sruCode2`.
`buildExportPayload()`: when building each `SieAccount`, call `account.setSRU([seed.sruCode, seed.sruCode2].findAll { it?.trim() })` — **not** `findAll { it }`, since a whitespace-only string is truthy in Groovy and would otherwise be written out as an invalid `#SRU` line. (Belt-and-suspenders: normalization at save time in §2 means `sru_code`/`sru_code2` should never actually contain whitespace-only values by the time export reads them, but the export code checks for it anyway rather than relying solely on the write-time guarantee.)

### 5a. Export-time warning for accounts missing an SRU code

Exporting today never fails and shouldn't start failing just because this feature exists — but silently producing a file the tax software will reject again (this app's original problem) isn't acceptable either.

**Confirmation must happen before the file is written, not after.** `exportFiscalYear()` currently builds the payload, renders it, and calls `Files.write(...)` before constructing `SieExportResult` — by the time any result object exists, the file is already on disk, too late for a "Export anyway?" prompt to mean anything. This mirrors the import side's existing shape: `previewSieImport(...)` returns a `SieImportPreview` that the dialog inspects *before* calling the mutating `importFile(...)`/`replaceFiscalYear(...)`. Following that same pattern:

- New `SieExportPreview` (in `SieImportExportModels.groovy`): `boolean legalFormUnset`, `List<String> accountsMissingSruCode`.
- New `SieImportExportService.previewSieExport(long fiscalYearId) -> SieExportPreview`: if `company.legalForm == null`, set `legalFormUnset = true` and leave `accountsMissingSruCode` empty (there's no table to check against without a legal form — this is a distinct condition from "checked, and nothing's missing"). Otherwise `legalFormUnset = false` and compute `accountsMissingSruCode` as below. No file I/O in either case.
- `SieExchangeDialog.exportRequested()` calls `previewSieExport(...)` first, and shows exactly one of two non-blocking confirmations before ever calling `exportFiscalYear(...)`:
  - `legalFormUnset == true` → *"Bolagsform är inte angiven. SRU-koder kan inte kontrolleras. Exportera ändå?"* — this is the case that matters most: it's what an existing company (including the one that triggered this whole feature — the migration deliberately leaves `legal_form = null`, see §1) sees on its very next export if nobody has visited company settings yet. Without this, `previewSieExport` would silently return an empty `accountsMissingSruCode` and the export would proceed with no warning at all, reproducing the original bug for exactly the user this feature exists for.
  - `legalFormUnset == false && accountsMissingSruCode` non-empty → *"N accounts used this year have no SRU code: [list]. Export anyway?"*
  - Neither condition → no confirmation, proceed straight to `exportFiscalYear(...)`, same as today.
  Cancelling either confirmation never reaches `exportFiscalYear()`, so no file is written. Export is never hard-blocked — the user can always proceed in both cases.

**Defining "used this year" correctly.** `loadAccounts()` returns the entire company chart of accounts regardless of activity (dozens of dormant BAS accounts in a typical install), so checking all of them would be noisy — but the original draft's "present in `closings` or `openings`" is wrong: `loadClosingBalances()` explicitly filters `and a.account_class in ('ASSET', 'LIABILITY', 'EQUITY')`, so income/expense (result) accounts are never in `closings`, and `openings` (opening balances) is never populated for result accounts either, by definition of double-entry bookkeeping. That would silently miss exactly the kind of cost/expense accounts (5xxx-8xxx) that carried real SRU codes in the Björn Lundén validation file. The correct "used this year" set is the union of:
- distinct `account_number` from `voucher_line` joined to `voucher` where `fiscal_year_id = ?` and `status in ('ACTIVE', 'CORRECTION')` (same scoping `loadBookedVouchers`/`loadClosingBalances` already use for booked activity), and
- distinct `account_number` from `opening_balance` for that fiscal year (covers balance accounts with a carried-forward balance but no movement this year).

When `company.legalForm` is set, `previewSieExport` computes this set, filters to accounts where `sru_code?.trim()` is blank (same trim-aware check as the export code, not a bare null/falsy check), and returns it as `accountsMissingSruCode`. When `legalForm` is unset, this computation is skipped entirely in favor of the `legalFormUnset` signal above — not because "nothing's missing" (the app has no way to know that), but because the dialog needs to distinguish "checked, all good" from "couldn't check at all."

---

## 6. SIE import

`sru_code`/`sru_code2` are manual, user-authoritative fields (§2) — the same status `vat_code` already has. Checking the precedent: `upsertAccounts()`'s existing three branches (new-account insert, manual-review update, plain refresh update) never touch `vat_code` on any *update* branch, only on insert — a reimported SIE file never clobbers a manually-set VAT code. `sru_code`/`sru_code2` follow the identical rule, resolving the earlier draft's contradiction (which said "manual, authoritative" in §2 but "import overwrites on every branch" here):

- **New account (insert branch only):** read `account.getSRU()` from the imported `SieAccount`. Each code is passed through `AccountService.normalizeSruCode(...)` then checked with `isValidSruCode(...)` — the same shared helpers `updateAccount` uses (§2), so the format rule can't drift between the manual and import paths. A code that fails validation is **dropped, not persisted**, and adds a warning to the existing `warnings` list (e.g. "Konto ${accountNumber}: ogiltig SRU-kod '${code}' i importfilen hoppades över.") — it does not abort the import, matching how other per-row issues in this method are already handled. The first/second-code assignment rule (first → `sru_code`, second → `sru_code2`, more than two → persist the first two and warn about the rest) is applied to the **surviving valid codes only**, after invalid ones have been filtered out. Empty/null list → both stay `null`.
- **Existing account (both update branches):** `sru_code`/`sru_code2` are never touched, regardless of what the imported file contains — identical to how `vat_code` is preserved today. A manually-corrected SRU code surviving a routine reimport is the same property that already protects `vat_code`, and this is exactly the kind of manually-verified, tax-filing-relevant field CLAUDE.md's data-integrity guidance is about protecting from silent overwrite.

---

## 7. Tests

- `DatabaseServiceTest` (or wherever migration application is already covered): V28/V29 apply cleanly on top of V27, and a fresh database ends up with both new columns via the normal `MIGRATIONS` bootstrap path.
- `CompanyServiceTest`: `legalForm`/`simplifiedAnnualReport` round-trip through `save()` (both create and update) and are visible via `findById()`, `listCompanies()`, and `listArchivedCompanies()` — this is the test that would have caught the missing `mapCompany`/`create`/`update`/listing wiring.
- `AccountServiceTest`: `sruCode`/`sruCode2` validation (accepts digits/null, rejects non-digits) including a whitespace-only input normalizing to stored `null`, and persistence round-trip via `updateAccount`/`findAccount`.
- `CompanyDialogTest` (or equivalent UI test if one exists for this dialog): saving through the dialog with a legal form selected persists it via `CompanyService`, for both the create and edit path — not just a direct `CompanyService.save()` call.
- `SruSuggestionParserTest` (unit-level, no spreadsheet needed): each parsing primitive from §3 point 1 individually — range, wildcard, wildcard-range, exclusion, per-segment sign tagging including the mid-list case.
- `SruSuggestionServiceTest`: expected row count per generated CSV; CSV parser reproduces all 208 pairs in the real-export fixture (§3); `8710`/`8750` explicitly return `[]` (known gap, not silently passing); sign-dependent account returns both candidates; unmapped account returns `[]`; `legalForm == null` returns `[]`; `company == null` returns `[]`; never suggests `sruCode2`.
- `ChartOfAccountsPanelTest` (or equivalent): `editSelectedAccount()` calls `sruSuggestionService.suggest(activeCompanyManager.activeCompany, ...)` and passes the result to `AccountEditorDialog.show(...)` — this is the test that would have caught the missing wiring between the service and the dialog.
- `SieImportExportServiceTest`: export emits 0/1/2 `#SRU` lines depending on which of `sruCode`/`sruCode2` are set; `previewSieExport()` returns `legalFormUnset = true` (and empty `accountsMissingSruCode`) when `company.legalForm` is null, vs. computing `accountsMissingSruCode` (including a result/expense account case, not just balance accounts) when it's set; `exportFiscalYear()` itself does not compute or require any of this (pure file-writing behavior unchanged); import persists SRU codes on new accounts and leaves them untouched on existing accounts regardless of file content (mirrors existing `vat_code`-preservation tests if present); warns-and-truncates beyond two valid codes; a malformed code in the imported file is dropped with a warning rather than persisted or aborting the import.
- `SieExchangeDialogTest.groovy`: update if it snapshots dialog fields affected by these changes; add coverage that `exportRequested()` calls `previewSieExport()` first and shows the legal-form-unset confirmation, the missing-codes confirmation, or neither, only proceeding to `exportFiscalYear()` when there's nothing to confirm or the user confirms.

---

## Open items resolved during brainstorming and review

- No auto-population of `sru_code` from suggestions — always an explicit user action (`[Använd]` button), never silently written. `sru_code2` has no suggestion source at all.
- No legal-form default/guess on existing companies — left unset.
- Sign-dependent suggestions are not resolved automatically against account balances — shown as multiple choices.
- Ambiguous parser rows are excluded and logged to `docs/SRU/unparsed.md`, never guessed.
- Confirmed via bas.se that `P1` in the source filenames does not mean "fiscal-year-period 1 of 4" — only one INK2/INK4/NE table exists per legal form; no additional period-specific files to fetch.
- INK2S (secondary tax-adjustment codes) has no publicly downloadable BAS kopplingstabell — explicitly out of scope for suggestions, manual-entry only via `sru_code2`.
- Both new migrations must be registered in `DatabaseService.MIGRATIONS`; there is no separate bootstrap schema file to update (verified `schema.sql` only creates the `schema_version` table — fresh databases run the full `MIGRATIONS` list too).
- `Company`/`Account` new fields are appended at the end of each class's field list, and every positional `new Company(...)`/`new Account(...)` call site (`CompanyService.mapCompany`, `AccountService.mapAccount`) is updated — both classes use `@Canonical` positional constructors, so this is not optional cleanup.
- Import never overwrites a manually-set `sru_code`/`sru_code2` on an existing account — only sets them on first insert, matching the existing `vat_code` precedent in `upsertAccounts()`.
- Export never blocks on missing SRU codes; it warns, scoped to accounts with actual activity in the exported fiscal year (not the full chart of accounts).
- The missing-SRU check runs via a new `previewSieExport()` called *before* `exportFiscalYear()` writes anything — `exportFiscalYear()` already writes the file before returning a result, so a field on its result object can't support a cancellable confirmation.
- "Used this year" for the missing-SRU check is voucher-line activity (ACTIVE/CORRECTION) unioned with opening balances — not `closings`/`openings`, which structurally exclude income/expense accounts (`loadClosingBalances` filters to ASSET/LIABILITY/EQUITY only).
- All three SELECTs feeding `CompanyService.mapCompany()` (`findById`, `listCompanies`, `listArchivedCompanies`) get the new columns, not just `findById`.
- `sru_code`/`sru_code2` are trim-to-null normalized at every write site (`AccountService.updateAccount`, SIE import) — a whitespace-only string is truthy in Groovy and must never reach the `account` table or an exported `#SRU` line; export and `previewSieExport` both check `?.trim()`, not bare truthiness.
- Import reuses `AccountService.normalizeSruCode`/`isValidSruCode` — the same digits-only format rule `updateAccount` enforces — rather than writing whatever a SIE file contains straight into the database via raw SQL. An invalid imported code is dropped with a per-row warning, not persisted, and does not abort the import.
- The parser's known gap (8710/8750 unmapped) and the fixture's expected match count must agree: the fixture holds only the 208 mappable pairs, and a separate test asserts the 2 gap accounts explicitly return no suggestion — a test can't simultaneously demand "matches all 210" and "2 of them have no mapping."
- `CompanyDialog`'s save handler builds `Company` via its positional constructor — the legal-form combo/checkbox values must be appended as the last two constructor arguments there, or the dialog's selection never reaches `Company` at all.
- `LegalForm.fromDatabaseValue` returns `null` on blank input, deliberately not following the `AccountingMethod`/`VatPeriodicity` precedent of defaulting to a concrete enum value — there is no safe default for legal form.
- `AccountEditorDialog` stays a stateless, static-only utility with no `Company`/`SruSuggestionService` dependency of its own — `ChartOfAccountsPanel` resolves `activeCompanyManager.activeCompany`, calls `SruSuggestionService.suggest(...)` itself, and passes the resulting `List<SruSuggestion>` into a new third parameter on `AccountEditorDialog.show(...)`. This requires a constructor-parameter change on `ChartOfAccountsPanel` and its `MainFrame` instantiation, not just a change inside the dialog.
- A company with `legalForm` unset (which, per §1, includes every existing company right after this feature ships — no backfill) must still get a non-blocking export confirmation, distinct from "checked, nothing missing": `SieExportPreview.legalFormUnset` drives its own confirmation message, so the exact scenario that triggered this feature doesn't silently export uncontrolled again just because nobody has visited company settings yet.
