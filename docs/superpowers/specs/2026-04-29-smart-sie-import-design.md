# Smart SIE Import Button — Design Spec

**Date:** 2026-04-29
**Issue:** #45 (comment from marcelo-7)

## Context

A user (marcelo-7) imported a SIE4 file, experimented with the application, and then could not re-import the same file. The existing "Ersätt år från SIE" (Replace Import) button already handles this case technically, but it was not discoverable enough — the user tried the normal Import button, hit a blocking error, and gave up.

The fix is to merge the two import buttons into one smart "Importera SIE" button that detects the situation and guides the user through the right recovery path (replace, or reopen-and-replace) without requiring them to know about separate buttons.

---

## Scope

- `SieExchangeDialog` — UI
- `SieImportExportService` — one new method, one small preview fix
- I18n message files (sv + default)
- Integration tests for new service behavior

Out of scope: MCP tools (they already use `previewSieImport` with token-based replace; no change needed).

---

## Decision Flow

After file selection and company resolution, the dialog runs `previewSieImport` before executing anything:

```
previewSieImport(replaceExisting=false)
  ├─ blockingIssues empty AND duplicate=false → importFile()  [no confirmation]
  ├─ blockingIssues non-empty OR duplicate=true
  │    → previewSieImport(replaceExisting=true)
  │        ├─ blocked: includes closing-entries issue
  │        │    (year open or closed, but has closing entries)
  │        │    → error dialog: cannot replace
  │        ├─ blocked: closed-year issue only (no closing entries)
  │        │    → confirm: "Reopen and replace? [purge counts]"
  │        │    → reopenAndReplaceFiscalYear()
  │        └─ not blocked
  │             → confirm: "Replace existing data? [purge counts]"
  │             → replaceFiscalYear()
  └─ blocked: SIE format / parse errors → error dialog (unchanged)
```

Note: when `previewSieImport(false)` detects a duplicate (`preview.duplicate=true`), `blockingIssues` is empty by design (the service skips content checks for duplicates on the first pass). The trigger for the replace path is therefore `!blockingIssues.empty || preview.duplicate`.

Fresh imports (no existing year, no duplicate) bypass all confirmation.

---

## Service Changes

### 1. `SieImportExportService.importBlockingIssues()`

**File:** `app/src/main/groovy/se/alipsa/accounting/service/SieImportExportService.groovy`

When `replaceExistingFiscalYear=true`, add a closing-entries check regardless of whether the year is closed. An open year can also have closing entries, and both cases block replacement. This lets the second preview call distinguish "closed, no closing entries (→ reopen+replace)" from "has closing entries (→ error)":

```groovy
// Add inside importBlockingIssues, after the existing closed-year check:
if (replaceExistingFiscalYear) {
  GroovyRowResult closingRow = sql.firstRow(
      'select count(*) as total from closing_entry where fiscal_year_id = ?',
      [fiscalYear.id])
  if ((closingRow.total as int) > 0) {
    issues << I18n.instance.format('sieImport.error.closingEntriesPreventReplace', fiscalYear.name)
  }
}
```

### 2. `SieImportExportService.reopenAndReplaceFiscalYear()`

New method that reopens the year and replaces its contents in a single transaction. This prevents a state where the year is left open but empty if the replace step fails.

```groovy
SieImportResult reopenAndReplaceFiscalYear(long companyId, Path filePath) {
  // 1. Reopen the year (validates no closing entries, throws if not allowed)
  // 2. Replace fiscal year contents (same as replaceFiscalYear)
  // Both steps inside withTransaction
}
```

The existing `FiscalYearService.reopenFiscalYear(sql, id)` and `FiscalYearReplacementService` are reused internally.

---

## UI Changes

**File:** `app/src/main/groovy/se/alipsa/accounting/ui/SieExchangeDialog.groovy`

### Remove
- `replaceImportButton` field declaration and `panel.add(replaceImportButton)`
- `replaceImportRequested()` method
- `importButton.addActionListener { importRequested() }` → replaced by new smart listener

### Add / Modify

**`importRequested()`** becomes a three-step SwingWorker chain:

1. **SW1** (existing): `peekSieCompany` → `resolveImportTarget`
2. **SW2** (new): `previewSieImport(targetId, path, false)` → analyze result on EDT
3. **SW3** (existing `doImport`, now parameterized): execute the chosen action

The analyze step (EDT, between SW2 and SW3):
- No blocking issues → call `doImport(path, targetId, false)`
- Blocked by data/duplicate → run a second SW to call `previewSieImport(targetId, path, true)`, then show confirmation dialog with purge counts → `doImport(path, targetId, true)` or `doReopenAndReplace(path, targetId)` depending on whether year is closed
- Blocked by format errors → `showError()`

**`doImport(path, targetId, replace)`** is extended with a third variant for the reopen case, or a new `doReopenAndReplace(path, targetId)` helper, both calling the appropriate service method.

Confirmation dialogs show actual purge counts from `FiscalYearPurgeSummary` (vouchers, opening balances, attachments, VAT periods, report archives).

---

## I18n

**Files:**
- `app/src/main/resources/i18n/messages_sv.properties`
- `app/src/main/resources/i18n/messages.properties`

### Remove
```
sieExchangeDialog.button.replaceImport
sieExchangeDialog.confirm.replaceImport.title
sieExchangeDialog.confirm.replaceImport.message
```

### Add
```
sieExchangeDialog.confirm.replace.title=Ersätta räkenskapsår?
sieExchangeDialog.confirm.replace.message=SIE-filen har redan importerats. Det befintliga räkenskapsåret innehåller {0} verifikationer, {1} ingående balanser, {2} bilagor, {3} momsperioder och {4} rapportarkiv. Allt detta tas bort och ersätts. Fortsätta?
sieExchangeDialog.confirm.reopenAndReplace.title=Låsa upp och ersätta räkenskapsår?
sieExchangeDialog.confirm.reopenAndReplace.message=Räkenskapsåret är stängt. Det kommer att låsas upp och sedan ersättas: {0} verifikationer, {1} ingående balanser, {2} bilagor, {3} momsperioder och {4} rapportarkiv tas bort. Fortsätta?
sieExchangeDialog.error.closingEntriesPreventReplace=Räkenskapsåret är stängt och har bokslutsposter. Det kan inte låsas upp eller ersättas.
sieImport.error.closingEntriesPreventReplace=Räkenskapsåret {0} har bokslutsposter och kan inte ersättas från SIE.
```

---

## Tests

**File:** `app/src/test/groovy/integration/se/alipsa/accounting/service/SieImportExportServiceTest.groovy`

- `previewWithReplaceDetectsClosingEntries()` — preview with `replaceExisting=true` returns closing-entries blocking issue when year has closing entries.
- `reopenAndReplaceFiscalYearSucceeds()` — closed year with no closing entries can be reopened and replaced.
- `reopenAndReplaceFiscalYearFailsIfClosingEntriesExist()` — closed year with closing entries throws; year remains closed and unmodified.

---

## Verification

1. `./gradlew test` — all existing tests pass; new service tests pass.
2. `./gradlew run` — open the SIE dialog:
   - Import a SIE file → succeeds, one "Importera SIE" button visible, no Replace button.
   - Import same file again → dialog shows "Replace?" confirm with actual counts → replace succeeds.
   - Close the fiscal year, try import again → dialog shows "Reopen and replace?" confirm → succeeds.
   - Close year + add closing entries, try import → error dialog shown, no action taken.
3. `./gradlew build` — Spotless and CodeNarc pass.
