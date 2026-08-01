# Corrected-voucher marker

## Problem

When a voucher is corrected, the application already leaves the original voucher's status as `ACTIVE` — only the new correction voucher is marked `CORRECTION`, and it carries a one-directional `original_voucher_id` pointer back to the voucher it corrects. `VoucherPanel` shows a "Corrects <number>" label, but only when you're looking *at* the correction voucher.

This creates a real risk: browsing a previous fiscal year's transaction report, or navigating directly to a voucher by number in the editor, gives no indication that a given `ACTIVE` voucher was later corrected. It's easy to use a since-corrected voucher as a reference for "how did I book this last time" and repeat the original mistake. The same risk exists for MCP-driven correction creation, not just the Swing GUI.

## Goals

- Surface "this voucher was corrected by <number(s)>" wherever a voucher can be viewed: the transaction report and the voucher editor.
- Handle the case where a voucher has been corrected more than once by different corrections (nothing currently prevents this — see below).
- Warn, without hard-blocking, before creating another correction against an already-corrected voucher — in both the Swing GUI and the MCP tool.

## Non-goals

- **Correction-of-correction chains are out of scope, because they're impossible today.** `VoucherService.createCorrectionVoucher` (`VoucherService.groovy:106`) rejects any original whose `status != ACTIVE`. A correction voucher's own status is always `CORRECTION`, so it can never itself be the target of a further correction. What *is* possible, and what this design supports, is multiple independent corrections against the same still-`ACTIVE` original (nothing re-checks or flips the original's status after the first correction).
- No change to `VoucherStatus.CANCELLED` handling — it's defined but not set by any code path today, so it's out of scope.
- No schema migration. `voucher.original_voucher_id` already exists and is sufficient; no reverse pointer or new column is added (see rejected approach below).
- No point-in-time/as-of-date report snapshotting (see semantics note in section 2) — out of scope beyond documenting the behavior this design implies.

## Semantics: this is an informational warning, not a correction/exclusion

The original voucher **stays `ACTIVE`** and continues to appear in reports and balances exactly as it does today — this design adds a visible note next to it, nothing more. The correction voucher's reversing + replacement lines are what actually change the books; the marker just makes that fact visible at the point where a human (or an MCP-driven agent) might otherwise treat the original as still-current guidance. For that reason the wording throughout is **"Corrected by <number>"**, not "Superseded by" — "superseded" reads as if the original were excluded or invalidated, which it isn't.

## Approach

**Query on demand, not a denormalized reverse pointer.** Add a lean lookup keyed off the existing `original_voucher_id` column. Both the editor and the report call into it (the report via a bulk, fiscal-year-scoped variant to avoid N+1 queries), and the MCP tool calls it too.

Rejected alternative: writing a `corrected_by_voucher_id` back onto the original voucher row when a correction is created. This would mutate a voucher row that's otherwise immutable once posted, would need to become a list/join table to support multiple corrections, and buys nothing over an indexed lookup at this data scale (single-company desktop accounting, embedded H2).

## Design

### 1. Data/query layer

`VoucherService.findCorrectionVoucherNumbers(long originalVoucherId): List<String>` — a narrow query, `SELECT voucher_number FROM voucher WHERE original_voucher_id = ? ORDER BY running_number`, with no join to `voucher_line` and no full `Voucher` hydration. Corrections always share the original's series (`insertVoucher` copies `original.seriesCode`), so ordering by `running_number` alone gives a stable, human-meaningful order without needing series comparison.

For the transaction report, a bulk variant scoped to the report's fiscal year builds `Map<Long, List<String>>` (original voucher id → correction voucher numbers) in a single query: `SELECT original_voucher_id, voucher_number, running_number FROM voucher WHERE fiscal_year_id = ? AND status = 'CORRECTION' ORDER BY running_number`, grouped by `original_voucher_id` in code. Corrections always share the original's fiscal year (enforced today via `ensureFiscalYearOpen(sql, original.fiscalYearId)` in `VoucherService.createCorrectionVoucher`), so scoping to the report's fiscal year is safe and keeps this a single extra query per report run.

### 2. Transaction report surfacing

`ReportDataService.buildTransactionReport` (`ReportDataService.groovy:1067`) currently writes the raw `PostingLine.status` enum string (`ACTIVE`/`CORRECTION`, unlocalized) straight into the status column. This changes to:

- If the row's `voucherId` is a key in the corrections map: render `I18n.format('transactionReport.status.correctedBy', correctionNumbers.join(', '))`, e.g. "Korrigerad av A12, A15".
- Otherwise: render via new localized keys `transactionReport.status.active` / `transactionReport.status.correction`, replacing the current unlocalized raw-enum leak.

New i18n keys (`messages.properties` and `messages_sv.properties`, following the existing `transactionReport.column.*` naming):
- `transactionReport.status.active`
- `transactionReport.status.correction`
- `transactionReport.status.correctedBy` (with `{0}` placeholder)

**Report semantics note:** the report is generated live, at print/view time, from current data — it has no notion of "what was known as of the report's date range." A correction always carries the *original's* accounting date, but can be created much later (e.g. reviewing FY2023 during FY2026 close). So a report run today for an old period can show a "Korrigerad av" marker for a row that, at the time that period was originally closed, had no correction yet. This is intentional — the entire point of the feature is to warn based on present-day knowledge when someone is looking at old data as a reference — but it means the marker is not a faithful historical record of what the books looked like at any past moment. Worth calling out explicitly so it isn't later mistaken for a bug.

### 3. Voucher editor reciprocal marker

Add a `correctedByLabel` JLabel next to the existing `correctsLabel` (`VoucherPanel.groovy:108`, header area built around line 311-320), styled in the same warning amber as `unsavedLabel` (`new Color(180, 83, 9)`).

In `showVoucher()` (~line 775), alongside the existing "corrects" lookup, call `voucherService.findCorrectionVoucherNumbers(v.id)`. If non-empty: set `correctedByLabel.text` via `I18n.format('voucherPanel.label.correctedBy', numbers.join(', '))` and make it visible. If empty: hide it. Reset alongside `correctsLabel` in `showBlankVoucher()` (~line 816-818).

`correctsLabel` and `correctedByLabel` are mutually exclusive by construction, not just by convention: `correctsLabel` is only shown on a voucher whose own `status == CORRECTION`, and (per the Non-goals section) a `CORRECTION`-status voucher can never appear as a key in another voucher's corrections list, since it can never itself be corrected. A given voucher will therefore show at most one of the two labels.

New i18n key: `voucherPanel.label.correctedBy` (both locale files), following the `voucherPanel.label.corrects` pattern.

### 4. Warn before creating another correction

**GUI:** in the `correctionButton` click handler (`VoucherPanel.groovy:356-361`), before invoking `voucherEditorActions.createCorrection()`: call `voucherService.findCorrectionVoucherNumbers(currentVoucher.id)`. If non-empty, show a `JOptionPane.showConfirmDialog` (same pattern as the existing void-confirmation at `VoucherPanel.groovy:1117`) naming the existing correction voucher number(s) and asking whether to proceed. On "No" or dialog dismissal, abort without creating another correction.

The confirmation predicate is extracted as a small method (e.g. `confirmRecorrection(List<String> existingNumbers): boolean`) separate from the `JOptionPane` call itself, so the decision logic is unit-testable without driving a real dialog.

New i18n key: `voucherPanel.confirm.alreadyCorrected` (with `{0}` placeholder for the existing correction voucher number(s)), both locale files.

**MCP:** `AccountingMcpTools.createCorrectionVoucher` (`AccountingMcpTools.groovy:628`) calls `voucherService.createCorrectionVoucher()` directly today, with no equivalent check — an MCP-driven caller (human or AI agent) currently gets no warning before creating a redundant correction. Add an optional `force` boolean argument to the tool. Before calling the service:

- Call `voucherService.findCorrectionVoucherNumbers(originalVoucherId)`.
- If non-empty and `force` is not `true`: return `[ok: false, warning: true, existing_corrections: [...], errors: ["This voucher was already corrected by <numbers>. Pass force: true to create another correction anyway."]]` without creating anything.
- Otherwise: proceed exactly as today.

This mirrors the GUI's warn-don't-block semantics as an explicit parameter instead of a dialog, and requires no change to `VoucherService.createCorrectionVoucher` itself — the check lives at each call site (GUI button handler, MCP tool method), same as today's pattern where the service has no opinion on confirmation UX.

### 5. Testing

- `VoucherServiceTest` / `VoucherServiceUnitTest`: `findCorrectionVoucherNumbers` — empty for an uncorrected voucher, one entry for a single correction, multiple entries (in `running_number` order) for a voucher corrected more than once.
- `ReportDataService` test: transaction report status column shows "Korrigerad av X" (or English equivalent) for a corrected original's row; plain localized "Aktiv"/"Korrigering" otherwise; joins multiple correction numbers when more than one exists.
- Extend `VoucherPanelNavigationTest` (which already has a `correctsLabelUpdatesAfterALocaleSwitch`-style test for `correctsLabel`) with an equivalent test for `correctedByLabel`: visibility, text content, locale-switch behavior; and a test confirming a `CORRECTION`-status voucher never shows `correctedByLabel` alongside `correctsLabel`.
- The `JOptionPane`-driven re-correction confirmation itself has no precedent for headless testing in this codebase; verify the extracted predicate method in isolation, and manually verify the actual dialog in the running app per the project's Swing-change verification guidance.
- MCP tool test: `create_correction_voucher` without `force` against an already-corrected voucher returns `ok: false` with the existing correction numbers and creates nothing; with `force: true` it proceeds and creates the correction as today.

## Open questions

None outstanding — all findings from spec review were resolved above.
