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
- `AccountEditorDialog` / `ChartOfAccountsPanel` — SRU code fields + suggestion hint
- `CompanyDialog` — legal form selector

Out of scope: automatic resolution of sign-dependent SRU fields against actual account balances (suggestion shows both candidates, user picks); per-fiscal-year legal form history (legal form lives on `Company`, not versioned); any legal forms beyond AB/enskild firma/handelsbolag-KB; MCP tool surface (can be extended later if needed, not required for this fix); blocking export outright when SRU codes are missing (warn only, see §5a).

---

## 1. Company legal form

`Company.groovy` — new fields appended **at the end** of the field list (after `accountingMethod`), not inserted in the middle, because `CompanyService.mapCompany()` builds `Company` via its `@Canonical` positional all-args constructor; inserting fields anywhere but the end would silently misassign every field after the insertion point:
```groovy
LegalForm legalForm            // nullable — unset until user configures it
boolean simplifiedAnnualReport = false   // only meaningful when legalForm == ENSKILD_FIRMA (K1 vs EJ_K1)
```

New enum `LegalForm { AKTIEBOLAG, ENSKILD_FIRMA, HANDELSBOLAG_KB }`.

Migration `V28__company_legal_form.sql` (**registered in `DatabaseService.MIGRATIONS` as version 28** — this list is the only thing that applies migrations, for both upgraded and brand-new databases; a new DB bootstraps by running every entry in `MIGRATIONS` from V1, there is no separate full-schema file to update):
```sql
alter table company add column legal_form varchar(20);
alter table company add column simplified_annual_report boolean not null default false;
```

No backfill — existing companies get `legal_form = null` (no suggestions shown) until the user sets it explicitly in company settings. Guessing a default here (e.g. defaulting to AKTIEBOLAG because of the `Aktiekapital` account seen in this instance) would be wrong for other companies in a multi-company install and isn't worth the risk.

**Full persistence path (this is the part that makes the field actually work, not just declared):**
- `CompanyService.create()`: add `legal_form`, `simplified_annual_report` to the `insert into company (...)` column list and value list.
- `CompanyService.update()`: add both columns to the `update company set ...` clause and value list.
- `CompanyService.mapCompany()`: add `legal_form as legalForm, simplified_annual_report as simplifiedAnnualReport` to the `findById` SELECT, and append `LegalForm.fromDatabaseValue(row.get('legalForm') as String)` / `Boolean.TRUE == row.get('simplifiedAnnualReport')` as the last two positional constructor arguments (matching the appended field order above).

Without all three, `legalForm` never round-trips — it'd stay `null` forever regardless of what the user picks in the dialog, and `SruSuggestionService` would never return a suggestion.

Add a legal-form combo box + simplified-annual-report checkbox (enabled only when `ENSKILD_FIRMA` is selected) to `CompanyDialog.groovy`.

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

`AccountService`:
- `updateAccount(...)` gains `sruCode`/`sruCode2` parameters. Validate: each is null/blank, or digits only (matches `Fält-kod` format, e.g. `7261`); reject anything else with `IllegalArgumentException`.
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
3. A test fixture `app/src/test/resources/sru/ink2-real-export-fixture.csv` holds the (account_number, sru_code) pairs extracted from a real Björn Lundén SIE export for an aktiebolag (no balances/vouchers/company-identifying data, just the pairs) — 210 of them match a single INK2 field; an automated test asserts the parsed `ink2.csv` reproduces all 210. This is what caught the mid-list sign-tag parsing bug during design (see Context). No equivalent real-file fixture exists yet for INK4/NE — flagged as a known coverage gap, not silently assumed correct by analogy.

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

In `AccountEditorDialog`, next to the primary SRU code field, show a suggestion hint when `SruSuggestionService.suggest(...)` returns matches:
- Single match, no sign condition: `Förslag: 7261 [Använd]` — button fills the text field.
- Sign-dependent matches: both candidates shown with their condition label (e.g. "om nettobelopp +" / "om nettobelopp –"), each with its own `[Använd]`.
- No match (legal form unset, or account not covered by the table): no hint shown, field behaves as plain manual entry — same as today.

The secondary SRU code field has no suggestion hint (no source table, see §3) — plain manual entry only.

---

## 5. SIE export

`SieImportExportModels.AccountSeed` gains `String sruCode`, `String sruCode2`.
`SieImportExportService.loadAccounts()` selects `sru_code as sruCode, sru_code2 as sruCode2`.
`buildExportPayload()`: when building each `SieAccount`, call `account.setSRU([seed.sruCode, seed.sruCode2].findAll { it })` — emits 0, 1, or 2 `#SRU` lines depending on which are non-blank.

### 5a. Export-time warning for accounts missing an SRU code

Exporting today never fails and shouldn't start failing just because this feature exists — but silently producing a file the tax software will reject again (this app's original problem) isn't acceptable either. `loadAccounts()` returns the **entire** company chart of accounts regardless of activity (dozens of dormant BAS accounts in a typical install), so checking all of them would be noisy; the original SRU-kontroll screenshot only flagged accounts with a nonzero `UB/Res`, i.e. exactly the accounts already present in `buildExportPayload()`'s `closings`/`openings` maps for that fiscal year.

- `SieExportResult` gains `List<String> accountsMissingSruCode` — account numbers present in `closings` or `openings` for the exported fiscal year whose `sru_code` is blank, when `company.legalForm` is set (if `legalForm` is unset, the app can't know which accounts need a code at all, so the list is left empty rather than flagging everything).
- `SieExchangeDialog.exportRequested()` shows this list in a non-blocking confirmation (same UX family as the existing import-replace confirmations, e.g. `previewSieImport`) — "N accounts used this year have no SRU code: [list]. Export anyway?" — the user can proceed or cancel to go fix them first. Export is never hard-blocked.

---

## 6. SIE import

`sru_code`/`sru_code2` are manual, user-authoritative fields (§2) — the same status `vat_code` already has. Checking the precedent: `upsertAccounts()`'s existing three branches (new-account insert, manual-review update, plain refresh update) never touch `vat_code` on any *update* branch, only on insert — a reimported SIE file never clobbers a manually-set VAT code. `sru_code`/`sru_code2` follow the identical rule, resolving the earlier draft's contradiction (which said "manual, authoritative" in §2 but "import overwrites on every branch" here):

- **New account (insert branch only):** read `account.getSRU()` from the imported `SieAccount`; first code → `sru_code`, second code → `sru_code2`, more than two → persist the first two and add a warning to the existing `warnings` list noting the account number and the dropped extra codes, empty/null → both stay `null`.
- **Existing account (both update branches):** `sru_code`/`sru_code2` are never touched, regardless of what the imported file contains — identical to how `vat_code` is preserved today. A manually-corrected SRU code surviving a routine reimport is the same property that already protects `vat_code`, and this is exactly the kind of manually-verified, tax-filing-relevant field CLAUDE.md's data-integrity guidance is about protecting from silent overwrite.

---

## 7. Tests

- `DatabaseServiceTest` (or wherever migration application is already covered): V28/V29 apply cleanly on top of V27, and a fresh database ends up with both new columns via the normal `MIGRATIONS` bootstrap path.
- `CompanyServiceTest`: `legalForm`/`simplifiedAnnualReport` round-trip through `save()` (both create and update) and `findById()` — this is the test that would have caught the missing `mapCompany`/`create`/`update` wiring.
- `AccountServiceTest`: `sruCode`/`sruCode2` validation (accepts digits/null, rejects non-digits) and persistence round-trip via `updateAccount`/`findAccount`.
- `SruSuggestionParserTest` (unit-level, no spreadsheet needed): each parsing primitive from §3 point 1 individually — range, wildcard, wildcard-range, exclusion, per-segment sign tagging including the mid-list case.
- `SruSuggestionServiceTest`: expected row count per generated CSV; CSV parser reproduces all 210 pairs in the real-export fixture (§3); sign-dependent account returns both candidates; unmapped account returns `[]`; `legalForm == null` returns `[]`; never suggests `sruCode2`.
- `SieImportExportServiceTest`: export emits 0/1/2 `#SRU` lines depending on which of `sruCode`/`sruCode2` are set; `accountsMissingSruCode` includes only accounts with year activity and `legalForm` set; import persists SRU codes on new accounts and leaves them untouched on existing accounts regardless of file content (mirrors existing `vat_code`-preservation tests if present); warns-and-truncates beyond two codes.
- `SieExchangeDialogTest.groovy`: update if it snapshots dialog fields affected by these changes; add coverage for the new export confirmation dialog.

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
