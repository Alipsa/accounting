# Superseded voucher marker

## Problem

When a voucher is corrected, the application already leaves the original voucher's status as `ACTIVE` — only the new correction voucher is marked `CORRECTION`, and it carries a one-directional `original_voucher_id` pointer back to the voucher it corrects. `VoucherPanel` shows a "Corrects <number>" label, but only when you're looking *at* the correction voucher.

This creates a real risk: browsing a previous fiscal year's transaction report, or navigating directly to a voucher by number in the editor, gives no indication that a given `ACTIVE` voucher was later superseded by a correction. It's easy to use a since-corrected voucher as a reference for "how did I book this last time" and repeat the original mistake.

## Goals

- Surface "this voucher was superseded by correction <number(s)>" wherever a voucher can be viewed: the transaction report and the voucher editor.
- Handle the case where a voucher has been corrected more than once (nothing currently prevents this — see below).
- Warn before creating a second correction against an already-corrected voucher, without blocking it outright.

## Non-goals

- No change to the correction-creation guard beyond a confirmation dialog (an original voucher can still be corrected more than once; we don't add a hard block).
- No change to `VoucherStatus.CANCELLED` handling — it's defined but not set by any code path today, so it's out of scope.
- No schema migration. `voucher.original_voucher_id` already exists and is sufficient; no reverse pointer or new column is added (see rejected approach below).

## Approach

**Query on demand, not a denormalized reverse pointer.** Add `VoucherService.findCorrectionVouchers(long originalVoucherId): List<Voucher>`, backed by `SELECT ... FROM voucher WHERE original_voucher_id = ? ORDER BY id`. Both the editor and the report call into this (the report via a bulk, fiscal-year-scoped variant to avoid N+1 queries).

Rejected alternative: writing a `corrected_by_voucher_id` back onto the original voucher row when a correction is created. This would mutate a voucher row that's otherwise immutable once posted, would need to become a list/join table to support multiple corrections, and buys nothing over an indexed lookup at this data scale (single-company desktop accounting, embedded H2).

## Design

### 1. Data/query layer

`VoucherService.findCorrectionVouchers(long originalVoucherId): List<Voucher>` — vouchers with `original_voucher_id = ?`, ordered by `id`.

For the transaction report, a bulk variant scoped to the report's fiscal year builds `Map<Long, List<String>>` (original voucher id → correction voucher numbers) in a single query. Corrections always share the original's fiscal year (enforced today via `ensureFiscalYearOpen(sql, original.fiscalYearId)` in `VoucherService.createCorrectionVoucher`), so scoping to the report's fiscal year is safe and keeps this a single extra query per report run.

### 2. Transaction report surfacing

`ReportDataService.buildTransactionReport` (`ReportDataService.groovy:1067`) currently writes the raw `PostingLine.status` enum string (`ACTIVE`/`CORRECTION`, unlocalized) straight into the status column. This changes to:

- If the row's `voucherId` is a key in the corrections map: render `I18n.format('transactionReport.status.supersededBy', correctionNumbers.join(', '))`, e.g. "Ersatt av A12, A15".
- Otherwise: render via new localized keys `transactionReport.status.active` / `transactionReport.status.correction`, replacing the current unlocalized raw-enum leak.

New i18n keys (`messages.properties` and `messages_sv.properties`, following the existing `transactionReport.column.*` naming):
- `transactionReport.status.active`
- `transactionReport.status.correction`
- `transactionReport.status.supersededBy` (with `{0}` placeholder)

### 3. Voucher editor reciprocal marker

Add a `supersededLabel` JLabel next to the existing `correctsLabel` (`VoucherPanel.groovy:108`, header area built around line 311-320), styled in the same warning amber as `unsavedLabel` (`new Color(180, 83, 9)`).

In `showVoucher()` (~line 775), alongside the existing "corrects" lookup, call `voucherService.findCorrectionVouchers(v.id)`. If non-empty: set `supersededLabel.text` via `I18n.format('voucherPanel.label.supersededBy', numbers.join(', '))` and make it visible. If empty: hide it. Reset alongside `correctsLabel` in `showBlankVoucher()` (~line 816-818).

`correctsLabel` and `supersededLabel` are independent and may both be visible at once — a correction voucher can itself later be corrected (a chain), so a voucher could correctly show both "Corrects A10" and "Superseded by A15" simultaneously.

New i18n key: `voucherPanel.label.supersededBy` (both locale files), following the `voucherPanel.label.corrects` pattern.

### 4. Warn before re-correcting

In the `correctionButton` click handler (`VoucherPanel.groovy:356-361`), before invoking `voucherEditorActions.createCorrection()`: call `voucherService.findCorrectionVouchers(currentVoucher.id)`. If non-empty, show a `JOptionPane.showConfirmDialog` (same pattern as the existing void-confirmation at `VoucherPanel.groovy:1117`) naming the existing correction voucher number(s) and asking whether to proceed. On "No" or dialog dismissal, abort without creating another correction.

The confirmation predicate is extracted as a small method (e.g. `confirmRecorrection(List<Voucher> existing): boolean`) separate from the `JOptionPane` call itself, so the decision logic is unit-testable without driving a real dialog.

New i18n key: `voucherPanel.confirm.alreadyCorrected` (with `{0}` placeholder for the existing correction voucher number(s)), both locale files.

### 5. Testing

- `VoucherServiceTest` / `VoucherServiceUnitTest`: `findCorrectionVouchers` — empty for an uncorrected voucher, one entry for a single correction, multiple entries for a voucher corrected more than once.
- `ReportDataService` test: transaction report status column shows "Ersatt av X" (or English equivalent) for a corrected original's row; plain localized "Aktiv"/"Korrigering" otherwise; joins multiple correction numbers when more than one exists.
- Extend `VoucherPanelNavigationTest` (which already has a `correctsLabelUpdatesAfterALocaleSwitch`-style test for `correctsLabel`) with an equivalent test for `supersededLabel`: visibility, text content, locale-switch behavior.
- The `JOptionPane`-driven re-correction confirmation itself has no precedent for headless testing in this codebase; verify the extracted predicate method in isolation, and manually verify the actual dialog in the running app per the project's Swing-change verification guidance.

## Open questions

None outstanding — all sections were reviewed and approved during brainstorming.
