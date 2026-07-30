# Voucher Series Combo Box — Design Spec

**Date:** 2026-07-30

## Context

The voucher editor's series control (`VoucherPanel.seriesField`) is a plain `JTextField`. There is no way to see which voucher series exist for a fiscal year without printing a report, because series aren't a managed list today — `VoucherService.ensureSeries()` (called from `insertVoucher`) silently creates a new series row (named `Serie <code>`) the first time someone types an unrecognized code and saves a voucher. The field is only enabled while composing a brand-new voucher (`seriesField.enabled = currentVoucher == null`); once an existing voucher is loaded it just displays that voucher's series code, read-only.

`VoucherService` already has what's needed to fix this without new service-layer work:
- `listSeries(fiscalYearId)` returns `List<VoucherSeries>` (id, code, name, next running number), ordered by code.
- `ensureSeries(fiscalYearId, seriesCode, seriesName = null)` is already public, validates the code (`normalizeSeriesCode`: uppercased, 1-8 chars, `A-Z0-9`), and is idempotent — calling it with a code that already exists just returns the existing row instead of erroring.
- `VoucherSeries.toString()` already renders `"CODE - Name"`.

## Scope

- `VoucherPanel.groovy`: replace `seriesField` (`JTextField`) with `seriesComboBox` (`JComboBox<VoucherSeries>`, **not editable**) plus a `+` button next to it.
- New `NewVoucherSeriesDialog.groovy`: small modal dialog (code + optional name) that calls `voucherService.ensureSeries(...)`.
- i18n additions for the new button and dialog (`messages.properties` / `messages_sv.properties`).
- Test coverage for the dialog's validation and the panel's combo behavior.

Out of scope: renaming/deleting/editing existing series, reordering series, any change to `VoucherService`'s series schema or `ensureSeries`/`listSeries` themselves, changing how series are stored on `Voucher`/`VoucherLine`.

## Behavior change: no more free-typed codes

Today, typing an unrecognized code into the field and saving silently creates that series. This spec **removes that path**: the combo is locked to existing series, and `+` is the only way to create a new one. This is the actual fix for "invisible series" — every series that ever gets created is now a deliberate, visible action, not a side effect of a typo.

**Empty-state default.** A brand new fiscal year has zero rows in `voucher_series` until the first voucher is ever saved (nothing seeds one today). With the combo locked, an empty list would block the very first voucher. To keep that zero-friction, a default `A` series is seeded — but **only** at the point a new, genuinely editable voucher is being composed, never merely from populating/viewing the combo (see `ensureDefaultSeriesForNewVoucher()` below). Every series *after* that first default one is explicit via `+`.

**Locked periods must not write anything.** The old text field being "enabled" while composing a new voucher in a locked period was harmless, because nothing persisted until `Save` — which is already blocked (`saveButton.enabled = !readOnly`). The `+` button breaks that assumption: it calls `ensureSeries` immediately on click, independent of whether the resulting voucher could ever be saved. Two rules follow, both detailed below: (1) `+` is only enabled when composing a new voucher *and* that voucher's date isn't in a locked period, and (2) populating the combo (`refreshSeriesComboBox()`) is a pure read with no side effects — the `A` seed never happens just from opening/browsing a fiscal year, locked or not.

## Components

### `VoucherPanel.groovy`

- Field: `private final JComboBox<VoucherSeries> seriesComboBox = new JComboBox<>()`, `seriesComboBox.editable = false`.
- New button `newSeriesButton`, built with the existing `navigationButton('+', 'voucherPanel.button.newSeries') { createNewSeries() }` helper, placed immediately after the combo in `buildHeaderBar()`.
- New `private boolean isNewVoucherPeriodLocked()`: extracts the exact lock check `applyReadOnlyState()` already performs for the "composing a new voucher" case (`accountingPeriodService.isDateLocked(activeCompanyManager.companyId, defaultDate())`, catching exceptions as locked/`true`, same log message). `applyReadOnlyState()`'s `else if (activeCompanyManager.fiscalYear != null)` branch is refactored to call this helper instead of inlining the try/catch, so there is exactly one implementation of "is a new voucher, today, allowed to be created". Returns `true` (fail closed) if there's no active fiscal year — nothing new-voucher-related should ever write when there's no fiscal year context.
- New `private void refreshSeriesComboBox(String preferredCode = null)` — **pure read, no writes, safe to call from any context including a locked/closed fiscal year:**
  1. Resolve `FiscalYear fy = activeCompanyManager.fiscalYear`; if null, clear the combo model and return.
  2. `List<VoucherSeries> series = voucherService.listSeries(fy.id)` (may be empty — that's fine here; seeding is a separate, gated step, see `ensureDefaultSeriesForNewVoucher()` below).
  3. Rebuild the combo model from `series`, which may leave it empty.
  4. Select `preferredCode` if given and present in the list; else keep the previous selection if still present; else select the first item if any exist.
  - Called as the **first statement** of `reloadVoucherList()`, before `cancelBalancePreload()` and, critically, before any `showBlankVoucher()`/`showVoucher()`/`navigation.select(...)` call in that method. `reloadVoucherList()`'s existing body calls one of those `show*` methods as its last action in every branch (not after some later "end" of the method) — placing the refresh anywhere but first would let `showEmptyVoucher()`/`showVoucher()` run against the *previous* fiscal year's still-loaded combo items when switching fiscal years, which is wrong in two ways: `ensureDefaultSeriesForNewVoucher()` would see non-zero `itemCount` (the old year's series) and decline to seed `A` for a genuinely empty new year, and `showEmptyVoucher()`/`showVoucher()` would preview/select a stale series that a moment later gets wiped out when the combo is (too late) rebuilt. Calling it first means `reloadVoucherList()` covers construction, fiscal-year switch, and company switch (it already runs on all three via the existing `propertyChange` listener) with the combo always reflecting the *target* fiscal year before anything reads it. Called again after a successful `createNewSeries()` (passing the new code as `preferredCode`).
- New `private void ensureDefaultSeriesForNewVoucher()` — the only place that seeds the default `A` series, and the only place besides `createNewSeries()` that writes to `voucher_series`:
  1. If `activeCompanyManager.fiscalYear == null` or `seriesComboBox.itemCount > 0`, return (nothing to do — either no context, or series already exist so there's nothing to default).
  2. If `isNewVoucherPeriodLocked()`, return **without seeding** — an empty combo in a locked period is fine: `Save` is already disabled by `readOnly`, so there is no new voucher this could mislead.
  3. Otherwise: `voucherService.ensureSeries(fy.id, 'A')`, then `refreshSeriesComboBox('A')`.
  - Called at the start of `showEmptyVoucher()`, before anything reads the combo's selection (see below) — the only call site, since it's specifically about preparing a *new* voucher.
- New `private void createNewSeries()`:
  - Guards on `activeCompanyManager.fiscalYear != null` (button is only enabled when it is, but double-checked defensively).
  - Shows `NewVoucherSeriesDialog`, which returns the created/matched `VoucherSeries` or `null` if cancelled.
  - On success: `refreshSeriesComboBox(result.seriesCode)`.
- Read/write sites switch from `seriesField.text` to the combo's selection:
  - `showVoucher(v)`: select the item whose `seriesCode == v.seriesCode` (helper `selectSeriesCode(String code)` that falls back to leaving the current selection if no match — shouldn't happen since a voucher's series always belongs to its own fiscal year, but fail-soft rather than throw).
  - `showEmptyVoucher()`: call `ensureDefaultSeriesForNewVoucher()` **first**. Then, instead of hardcoding `'A'` anywhere (the bug being fixed here — the old code called `previewNextVoucherNumber('A')` unconditionally, regardless of what was actually selected), read the combo's actual state: `VoucherSeries selected = seriesComboBox.selectedItem as VoucherSeries`. If non-null, `nextNumber = previewNextVoucherNumber(selected.seriesCode)`; if null (fiscal year has no series and the period is locked, so `ensureDefaultSeriesForNewVoucher()` correctly declined to seed), `nextNumber` is a blank/placeholder string — there is nothing to preview because nothing can be saved. This guarantees the displayed number always matches whatever series will actually be used if `Save` is (or becomes) available.
  - `duplicateVoucher()`: `selectSeriesCode(source.seriesCode ?: 'A')` — unaffected by this fix, `source.seriesCode` is always a real, already-persisted series from an existing voucher, never a guess.
  - `snapshotDraft()` / `applyDraft()`: read/select via `(seriesComboBox.selectedItem as VoucherSeries)?.seriesCode` — the draft's `seriesCode` stays a plain `String`, unchanged on the `VoucherDraftMapper` boundary.
  - `VoucherEditorActions` construction (`seriesSupplier`): `{ (seriesComboBox.selectedItem as VoucherSeries)?.seriesCode ?: 'A' }` — `VoucherEditorActions`'s own signature (`Supplier<String>`) is unchanged. The `?: 'A'` fallback is now unreachable in practice for a savable voucher (by the time `Save` is enabled, `ensureDefaultSeriesForNewVoucher()` has guaranteed a selection), but kept as a defensive default matching the supplier's existing contract.
- `applyReadOnlyState()`:
  - `seriesComboBox.enabled = currentVoucher == null` (unchanged rule — merely *selecting* among already-existing series doesn't write anything, so it doesn't need the lock check; the write only happens at `Save`, already gated by `saveButton.enabled = !readOnly`).
  - `newSeriesButton.enabled = currentVoucher == null && !readOnly` — this is the fix for the "+" button: creating a series is an immediate write, so it must respect the same lock check as `Save`, not just "is this a new voucher".
- Bonus (small, low-risk UX win enabled by the structured model): add an item listener on `seriesComboBox` that updates `voucherNumberLabel`/`jumpField` live via the existing `previewNextVoucherNumber(seriesCode)`. Must guard on `currentVoucher == null` (only meaningful while composing a new voucher) **and** not fire during programmatic selection from `showVoucher`/`duplicateVoucher`/`applyDraft`/`refreshSeriesComboBox` — those already set the correct label themselves (e.g. `showVoucher` sets it from the loaded voucher's real number, not a preview) and must not have it overwritten. The existing codebase convention for this is a reentrancy guard flag set around programmatic `setSelectedItem` calls; the implementation plan should follow it.

### `NewVoucherSeriesDialog.groovy` (new file)

- Modal `JDialog`, sized/styled like existing small dialogs (e.g. `PeriodLockDialog`).
- Fields: series code (`JTextField`, required), series name (`JTextField`, optional).
- OK button: trims/uppercases the code client-side and checks it against `1-8 chars, A-Z0-9` before calling the service (same rule as `VoucherService.normalizeSeriesCode`, duplicated client-side only for immediate inline feedback — the service call remains the source of truth). On a validation failure, shows an inline error label rather than closing.
- Calls `voucherService.ensureSeries(fiscalYearId, code, name?.trim() ?: null)`. Any `IllegalArgumentException` from the service (defensive backstop) is shown the same way.
- If the entered code already exists, `ensureSeries` just returns that existing row — the dialog treats this as success (no special-cased "already exists" error), closes, and the panel selects it.
- Cancel / window-close returns `null`.
- Constructor takes the owner frame, `VoucherService`, and `fiscalYearId`; a static `static VoucherSeries showDialog(Frame owner, VoucherService voucherService, long fiscalYearId)` mirrors the calling convention other dialogs in this package use.

## i18n

New keys under the existing `voucherPanel.*` namespace, added to both `messages.properties` and `messages_sv.properties`:
- `voucherPanel.button.newSeries` (tooltip for the `+` button)
- `newVoucherSeriesDialog.title`, `.label.code`, `.label.name`, `.button.ok`, `.button.cancel`, `.error.invalidCode`

## Testing

- New unit test `NewVoucherSeriesDialogTest` (or equivalent headless-safe test) covering code validation (rejects empty/too-long/invalid-character codes) and the "existing code selects existing series" path, using a real `VoucherService` against a temp DB (matching how `VoucherPanelNavigationTest` is set up).
- Extend `VoucherPanelNavigationTest` (integration):
  - A fresh, open fiscal year with no vouchers yet shows the combo pre-populated with a single seeded `A` series, and the voucher-number preview reads `A-1`.
  - Creating a second series via `createNewSeries()`/the dialog makes it appear in the combo and become selected.
  - Switching fiscal year repopulates the combo with that year's series.
  - Combo and `+` button are disabled once an existing voucher is loaded, matching the current `seriesField` behavior.
  - **Locked-period regression (finding 1):** a fiscal year/period locked via `AccountingPeriodService` with zero existing series — merely opening/viewing it must not create a `voucher_series` row (assert `listSeries()` still returns empty after `reloadVoucherList()`/`showEmptyVoucher()`), and `newSeriesButton.enabled` must be `false`.
  - **Non-`A` default series regression (finding 2):** a fiscal year whose only series is `B` (seeded directly via `ensureSeries(fy.id, 'B')` in test setup, simulating an imported/legacy year) — `showEmptyVoucher()` must select `B` in the combo *and* the voucher-number preview must read `B-<next>`, never `A-*`. Then actually saving must produce a `B-*` voucher number, confirming the label and the save path agree.
  - **Stale-combo-on-fiscal-year-switch regression (finding 3):** with two fiscal years open in the same company/panel — year 1 has series `B` selected (no `A`), year 2 is brand new with zero series and open (not locked). Switching the active fiscal year from year 1 to year 2 (via `activeCompanyManager`, triggering `reloadVoucherList()`) must result in year 2's combo showing the freshly-seeded `A` (not a leftover `B` from year 1, and not an empty/unselected combo), and the voucher-number preview must read `A-1`. This specifically catches `refreshSeriesComboBox()` running after `showEmptyVoucher()` instead of before.
