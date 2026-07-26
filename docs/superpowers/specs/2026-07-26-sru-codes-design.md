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

## Scope

- `Company` — legal form + simplified-annual-report flag
- `Account` — sru code field
- New bundled SRU suggestion data (parsed once from the xlsx files) + lookup service
- `SieImportExportService` — export emits `#SRU`, import persists `#SRU` if present
- `AccountEditorDialog` / `ChartOfAccountsPanel` — SRU code field + suggestion hint
- Company settings UI — legal form selector

Out of scope: automatic resolution of sign-dependent SRU fields against actual account balances (suggestion shows both candidates, user picks); per-fiscal-year legal form history (legal form lives on `Company`, not versioned); any legal forms beyond AB/enskild firma/handelsbolag-KB; MCP tool surface (can be extended later if needed, not required for this fix).

---

## 1. Company legal form

`Company.groovy`:
```groovy
LegalForm legalForm            // nullable — unset until user configures it
boolean simplifiedAnnualReport = false   // only meaningful when legalForm == ENSKILD_FIRMA (K1 vs EJ_K1)
```

New enum `LegalForm { AKTIEBOLAG, ENSKILD_FIRMA, HANDELSBOLAG_KB }`.

Migration `V28__company_legal_form.sql`:
```sql
alter table company add column legal_form varchar(20);
alter table company add column simplified_annual_report boolean not null default false;
```

No backfill — existing companies get `legal_form = null` (no suggestions shown) until the user sets it explicitly in company settings. Guessing a default here (e.g. defaulting to AKTIEBOLAG because of the `Aktiekapital` account seen in this instance) would be wrong for other companies in a multi-company install and isn't worth the risk.

Add a legal-form combo box + simplified-annual-report checkbox (enabled only when `ENSKILD_FIRMA` is selected) to `CompanyDialog.groovy`.

---

## 2. Per-account SRU code (manual, authoritative)

This is the field that actually gets written to `#SRU` on export, independent of whether a suggestion exists — mirrors the existing `vat_code` pattern exactly.

Migration `V29__account_sru_code.sql`:
```sql
alter table account add column sru_code varchar(10);
```

`Account.groovy`: add `String sruCode`.

`AccountService`:
- `updateAccount(...)` gains an `sruCode` parameter. Validate: null/blank, or digits only (matches `Fält-kod` format, e.g. `7261`); reject anything else with `IllegalArgumentException`.
- `searchAccounts` / `findAccount` SELECT + `mapAccount` include `sru_code as sruCode`.

`AccountEditorDialog`: add an SRU-code `JTextField`, following the existing `addRow(...)` pattern used for `name`/`class`/`normal`/`vatCode`/`active`/`review`. `AccountEditorResult` gains `sruCode`.

`ChartOfAccountsPanel`: add an "SRU" column to `AccountTableModel`; pass `edited.sruCode` through in `editSelectedAccount()`.

i18n: add `chartOfAccountsPanel.table.sruCode` and `chartOfAccountsPanel.edit.description.sruCode` keys (sv + default bundles).

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

**Correctness check:** an automated test asserts the parsed `ink2.csv` reproduces the 8 known-correct codes from the original SRU-kontroll screenshot:
`1630→7261, 1930→7281, 2081→7301, 2091→7302, 2099→7302, 2510→7368, 2519→7368, 2650→7369`.

Regenerating these CSVs (e.g. when BAS republishes updated tables next year) is a manual, one-off task — not ongoing build infrastructure, consistent with how the existing BAS chart-of-accounts import is already a manual, user-triggered action rather than an automated sync.

### Lookup service

New `SruSuggestionService`:
```groovy
List<SruSuggestion> suggest(Company company, String accountNumber)
```
- Resolves the CSV by `company.legalForm` (+ `simplifiedAnnualReport` when `ENSKILD_FIRMA`); returns `[]` if `legalForm` is unset.
- Returns all matching rows for the account number. If more than one exists for different sign conditions, all are returned (UI shows both candidates); no automatic resolution against actual account balances in this pass.

`SruSuggestion`: `fieldCode`, `description`, `signCondition`.

---

## 4. UI: showing suggestions

In `AccountEditorDialog`, next to the SRU code field, show a suggestion hint when `SruSuggestionService.suggest(...)` returns matches:
- Single match, no sign condition: `Förslag: 7261 [Använd]` — button fills the text field.
- Sign-dependent matches: both candidates shown with their condition label (e.g. "om nettobelopp +" / "om nettobelopp –"), each with its own `[Använd]`.
- No match (legal form unset, or account not covered by the table): no hint shown, field behaves as plain manual entry — same as today.

---

## 5. SIE export

`SieImportExportModels.AccountSeed` gains `String sruCode`.
`SieImportExportService.loadAccounts()` selects `sru_code as sruCode`.
`buildExportPayload()`: when building each `SieAccount`, call `account.setSRU([seed.sruCode])` if `seed.sruCode` is non-blank.

---

## 6. SIE import

In `upsertAccounts()`, read `account.getSRU()` from the imported `SieAccount`:
- Empty/null → leave `sru_code` untouched (insert: null; update: unchanged).
- One code → persist to `sru_code` in all three existing branches (new account insert, manual-review update, plain refresh update).
- More than one code → persist the first, add a warning to the existing `warnings` list (same pattern as other per-row import warnings in this method) noting the account number and the dropped extra codes.

---

## 7. Tests

- `AccountServiceTest`: `sruCode` validation (accepts digits/null, rejects non-digits) and persistence round-trip via `updateAccount`/`findAccount`.
- `SruSuggestionServiceTest`: CSV parser reproduces the 8 known INK2 codes; sign-dependent account returns both candidates; unmapped account returns `[]`; `legalForm == null` returns `[]`.
- `SieImportExportServiceTest`: export emits `#SRU` for accounts with a code and omits it otherwise; import persists a single `#SRU` code and warns-and-truncates on multiple codes.
- `SieExchangeDialogTest.groovy`: update if it snapshots dialog fields affected by these changes.

---

## Open items resolved during brainstorming

- No auto-population of `sru_code` from suggestions — always an explicit user action (`[Använd]` button), never silently written.
- No legal-form default/guess on existing companies — left unset.
- Sign-dependent suggestions are not resolved automatically against account balances — shown as multiple choices.
- Ambiguous parser rows are excluded and logged to `docs/SRU/unparsed.md`, never guessed.
