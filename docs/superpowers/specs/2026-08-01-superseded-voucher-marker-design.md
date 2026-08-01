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

The original voucher **stays `ACTIVE`** and continues to appear in reports and balances exactly as it does today — this design adds a visible note next to it, nothing more. The correction voucher contains lines that reverse the original (mirrored debit/credit, per `VoucherService.groovy:115-127`) — it does not itself carry a "replacement"/corrected entry; that's recorded as a separate ordinary voucher if needed. The marker just makes the reversal visible at the point where a human (or an MCP-driven agent) might otherwise treat the original as still-current guidance. For that reason the wording throughout is **"Corrected by <number>"**, not "Superseded by" — "superseded" reads as if the original were excluded or invalidated, which it isn't.

## Approach

**Query on demand, not a denormalized reverse pointer.** Add a lean lookup keyed off the existing `original_voucher_id` column. Both the editor and the report call into it (the report via a bulk, fiscal-year-scoped variant to avoid N+1 queries), and the MCP tool calls it too.

Rejected alternative: writing a `corrected_by_voucher_id` back onto the original voucher row when a correction is created. This would mutate a voucher row that's otherwise immutable once posted, would need to become a list/join table to support multiple corrections, and buys nothing over an indexed lookup at this data scale (single-company desktop accounting, embedded H2).

## Design

### 1. Data/query layer

**`original_voucher_id` is the authoritative signal, not `status`.** Both queries below key off `original_voucher_id` alone, with no `status` filter, and deliberately match each other's semantics — this is the same conclusion the earlier "mutual exclusivity" discussion reached: the pairing of `original_voucher_id` and `status = CORRECTION` is an application-level invariant (`VoucherService.createCorrectionVoucher` always sets both together), not something the database enforces, so filtering on `status` would be redundant when the invariant holds and would silently hide a real correction reference if it were ever violated (e.g. by a data-repair bug). Filtering on `original_voucher_id` is the one true source of "this voucher corrects that one," per today's actual FK-style relationship.

`VoucherService.findCorrectionVoucherNumbers(long originalVoucherId): List<String>` — a narrow query, `SELECT voucher_number FROM voucher WHERE original_voucher_id = ? ORDER BY running_number`, with no join to `voucher_line` and no full `Voucher` hydration. Corrections always share the original's series (`insertVoucher` copies `original.seriesCode`), so ordering by `running_number` alone gives a stable, human-meaningful order without needing series comparison.

For the transaction report, a bulk variant scoped to the report's fiscal year builds `Map<Long, List<String>>` (original voucher id → correction voucher numbers) in a single query: `SELECT original_voucher_id, voucher_number, running_number FROM voucher WHERE fiscal_year_id = ? AND original_voucher_id IS NOT NULL ORDER BY running_number`, grouped by `original_voucher_id` in code. Corrections always share the original's fiscal year (enforced today via `ensureFiscalYearOpen(sql, original.fiscalYearId)` in `VoucherService.createCorrectionVoucher`), so scoping to the report's fiscal year is safe and keeps this a single extra query per report run.

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

Add a `correctedByLabel` JLabel next to the existing `correctsLabel` (`VoucherPanel.groovy:112`, header area built in `addCorrectsHeaderLabel` at `VoucherPanel.groovy:316-325`), styled in the same warning amber as `unsavedLabel` (`new Color(180, 83, 9)`).

This mirrors the existing `correctsLabel` pattern exactly, including its locale-switch handling — `correctsLabel` isn't just set once in `showVoucher()`; its text is rebuilt from the stored `correctsOriginalVoucherNumber` field whenever the UI locale changes, in `refreshCaptionLabels()` (`VoucherPanel.groovy:1462-1471`, called from `updateLabels()`). The new label needs the same two-part treatment, not just the `showVoucher()` half:

- A new field, e.g. `correctedByVoucherNumbers` (the joined display string, or null when there are none), set alongside `correctedByLabel` in `showVoucher()` (~line 775-791): call `voucherService.findCorrectionVoucherNumbers(v.id)`; if non-empty, store the joined numbers and set `correctedByLabel.text` via `I18n.format('voucherPanel.label.correctedBy', numbers.join(', '))`, visible; if empty, clear the field and hide the label.
- `refreshCaptionLabels()` gets an equivalent block to its existing `correctsOriginalVoucherNumber` block (`VoucherPanel.groovy:1468-1470`): if `correctedByVoucherNumbers != null`, rebuild `correctedByLabel.text` from it using the current locale. Without this, switching languages while a corrected voucher is displayed leaves the label's wording stuck in whatever locale was active when it was first shown.
- Reset alongside `correctsLabel`/`correctsOriginalVoucherNumber` in `showEmptyVoucher()` (`VoucherPanel.groovy:807-834`, the reset block at lines 824-826), **not** in `showBlankVoucher()` — `showBlankVoucher()` (`VoucherPanel.groovy:801-805`) is just a thin wrapper that calls `showEmptyVoucher()` after touching navigation state. `showEmptyVoucher()` is also called directly, bypassing that wrapper, from `restoreNavigationDraft()` (`VoucherPanel.groovy:1050-1067`) on both the no-draft and the draft-validation-failure paths. Resetting in `showEmptyVoucher()` itself covers all three entry points (blank voucher, no remembered draft, discarded invalid draft) in one place; resetting only in `showBlankVoucher()` would miss the other two and could leave a stale "Corrected by" label showing over a blank/restored draft.

`correctsLabel` and `correctedByLabel` are expected to be mutually exclusive in practice, but this rests on an application-level invariant, not a schema constraint or on what the GUI actually checks — worth being precise about rather than asserting it as guaranteed:

- `showVoucher()` (`VoucherPanel.groovy:782`) shows `correctsLabel` when `v.originalVoucherId != null`, *not* when `v.status == CORRECTION`. Those two happen to always agree today only because `createCorrectionVoucher` (`VoucherService.groovy:103-148`) sets both `original_voucher_id` and `status = CORRECTION` together, in the same transaction, every time — there's no `CHECK` constraint or other DB-level rule pairing them.
- Given that pairing holds, the exclusivity argument still follows: a voucher with `originalVoucherId != null` has `status == CORRECTION`, and (per the Non-goals section) a `CORRECTION`-status voucher can never itself be corrected, so it can never appear as a key in another voucher's corrections list.
- If that invariant were ever violated (a bug, or a manual data fix that sets one field without the other) the two labels could in principle both show for the same voucher. That's an acceptable failure mode — it's a display nicety, nothing downstream depends on the exclusivity — but it means "mutually exclusive by construction" overstates the guarantee. No enforcement work is proposed here; this is a documented data invariant, not a new invariant to add.

New i18n key: `voucherPanel.label.correctedBy` (both locale files), following the `voucherPanel.label.corrects` pattern.

### 4. Warn before creating another correction

**GUI, both call sites.** `VoucherService.createCorrectionVoucher` is called from two places in `VoucherPanel`, not one:
- the `correctionButton` click handler (`VoucherPanel.groovy:359-366`);
- `deleteOrCancelVoucher()`, wired to `voidButton` (`VoucherPanel.groovy:1121-1142`, listener at `VoucherPanel.groovy:370`). `voidButton` is currently hard-disabled (`voidButton.enabled = false` in `applyReadOnlyState()`), so this path isn't reachable through the running GUI today — but the method exists, calls the service directly, and would silently skip the warning the moment that button is re-enabled or the method gets called some other way. The fix applies to the method, not to the button's enabled state.

Both call sites route through one shared helper, e.g. `confirmRecorrectionIfNeeded(long voucherId): boolean`, added to `VoucherPanel`:
- calls `voucherService.findCorrectionVoucherNumbers(voucherId)`;
- if empty, returns `true` immediately (no dialog);
- if non-empty, shows a `JOptionPane.showConfirmDialog` (same pattern as the existing void-confirmation dialog already in `deleteOrCancelVoucher` at `VoucherPanel.groovy:1126`) naming the existing correction voucher number(s), and returns whether the user chose to proceed.

Both `correctionButton`'s listener and `deleteOrCancelVoucher()` call this helper first and abort (no call to `createCorrection()` / `createCorrectionVoucher()`) if it returns `false`. The confirmation predicate itself (given a list of existing numbers, should we proceed) is factored out as a plain method separate from the `JOptionPane` call, so the decision logic is unit-testable without driving a real dialog.

New i18n key: `voucherPanel.confirm.alreadyCorrected` (with `{0}` placeholder for the existing correction voucher number(s)), both locale files.

**MCP:** `AccountingMcpTools.createCorrectionVoucher` (`AccountingMcpTools.groovy:628`) calls `voucherService.createCorrectionVoucher()` directly today, with no equivalent check — an MCP-driven caller (human or AI agent) currently gets no warning before creating a redundant correction. Two changes are needed together, since the second is easy to miss:

- **Schema:** the tool's parameter map is declared separately in `McpToolDefinitions.groovy:141-148` (`create_correction_voucher`'s `toolDef(...)` call), currently only `original_voucher_id` (required) and `description` (optional). Add `force: optBoolParam('Set to true to create another correction even though this voucher already has one or more. Required when create_correction_voucher previously returned ok:false with warning:true.')` to that params map — `optBoolParam` already exists as a helper (`McpToolDefinitions.groovy:245`) and is used the same way elsewhere.
- **Behavior:** in `AccountingMcpTools.createCorrectionVoucher` (`AccountingMcpTools.groovy:628`), before calling the service:
  - Call `voucherService.findCorrectionVoucherNumbers(originalVoucherId)`.
  - If non-empty and `force` is not `true`: return `[ok: false, warning: true, existing_corrections: [...], errors: ["This voucher was already corrected by <numbers>. Pass force: true to create another correction anyway."]]` without creating anything.
  - Otherwise: proceed exactly as today.

This mirrors the GUI's warn-don't-block semantics as an explicit parameter instead of a dialog, and requires no change to `VoucherService.createCorrectionVoucher` itself — the check lives at each call site (the shared `confirmRecorrectionIfNeeded` helper covering both `correctionButton` and `deleteOrCancelVoucher`/`voidButton`, plus the MCP tool method), same as today's pattern where the service has no opinion on confirmation UX.

### 5. Testing

- `VoucherServiceTest` / `VoucherServiceUnitTest`: `findCorrectionVoucherNumbers` — empty for an uncorrected voucher, one entry for a single correction, multiple entries (in `running_number` order) for a voucher corrected more than once.
- `ReportDataService` test: transaction report status column shows "Korrigerad av X" (or English equivalent) for a corrected original's row; plain localized "Aktiv"/"Korrigering" otherwise; joins multiple correction numbers when more than one exists.
- Extend `VoucherPanelNavigationTest` (which already has a `correctsLabelUpdatesAfterALocaleSwitch`-style test for `correctsLabel`) with equivalent tests for `correctedByLabel`: visibility and text content when showing a corrected voucher; a locale-switch test mirroring `correctsLabelUpdatesAfterALocaleSwitch` (switch locale while a corrected voucher is displayed, assert the label text is rebuilt in the new language); a reset test showing the label is cleared after navigating to a blank voucher *and* after a discarded/no-draft restoration (`restoreNavigationDraft()`'s two `showEmptyVoucher()` calls), not just via `showBlankVoucher()`; and a test confirming a `CORRECTION`-status voucher never shows `correctedByLabel` alongside `correctsLabel`.
- The `JOptionPane`-driven re-correction confirmation itself has no precedent for headless testing in this codebase; verify the extracted predicate method in isolation (covering both the `correctionButton` and `deleteOrCancelVoucher` call sites use the same helper), and manually verify the actual dialog in the running app per the project's Swing-change verification guidance.
- MCP tool test: `create_correction_voucher` without `force` against an already-corrected voucher returns `ok: false` with the existing correction numbers and creates nothing; with `force: true` it proceeds and creates the correction as today; a schema/tool-listing test confirms `force` appears as an optional boolean parameter in the published tool definition.

## Open questions

None outstanding — all findings from spec review were resolved above.
