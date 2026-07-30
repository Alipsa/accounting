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

**Empty-state default.** A brand new fiscal year has zero rows in `voucher_series` until the first voucher is ever saved (nothing seeds one today). With the combo locked, an empty list would block the very first voucher. To keep that zero-friction: when `refreshSeriesComboBox()` finds the list empty for the active fiscal year, it calls `ensureSeries(fiscalYearId, 'A')` once to seed the same default the app already gives implicitly today, then populates the combo with that one entry, selected. Every series *after* that first default one is explicit via `+`.

## Components

### `VoucherPanel.groovy`

- Field: `private final JComboBox<VoucherSeries> seriesComboBox = new JComboBox<>()`, `seriesComboBox.editable = false`.
- New button `newSeriesButton`, built with the existing `navigationButton('+', 'voucherPanel.button.newSeries') { createNewSeries() }` helper, placed immediately after the combo in `buildHeaderBar()`.
- New `private void refreshSeriesComboBox(String preferredCode = null)`:
  1. Resolve `FiscalYear fy = activeCompanyManager.fiscalYear`; if null, clear the combo model and return.
  2. `List<VoucherSeries> series = voucherService.listSeries(fy.id)`.
  3. If empty: `series = [voucherService.ensureSeries(fy.id, 'A')]`.
  4. Rebuild the combo model from `series`.
  5. Select `preferredCode` if given and present in the list; else keep the previous selection if still present; else select the first item.
  - Called once at the end of `reloadVoucherList()` (covers construction, fiscal-year switch, and company switch — `reloadVoucherList()` already runs on all three via the existing `propertyChange` listener), and again after a successful `createNewSeries()` (passing the new code as `preferredCode`).
- New `private void createNewSeries()`:
  - Guards on `activeCompanyManager.fiscalYear != null` (button is only enabled when it is, but double-checked defensively).
  - Shows `NewVoucherSeriesDialog`, which returns the created/matched `VoucherSeries` or `null` if cancelled.
  - On success: `refreshSeriesComboBox(result.seriesCode)`.
- Read/write sites switch from `seriesField.text` to the combo's selection:
  - `showVoucher(v)`: select the item whose `seriesCode == v.seriesCode` (helper `selectSeriesCode(String code)` that falls back to leaving the current selection if no match — shouldn't happen since a voucher's series always belongs to its own fiscal year, but fail-soft rather than throw).
  - `showEmptyVoucher()`: select `'A'` via `selectSeriesCode('A')` (still meaningful post-refresh since the combo is guaranteed non-empty).
  - `duplicateVoucher()`: `selectSeriesCode(source.seriesCode ?: 'A')`.
  - `snapshotDraft()` / `applyDraft()`: read/select via `(seriesComboBox.selectedItem as VoucherSeries)?.seriesCode` — the draft's `seriesCode` stays a plain `String`, unchanged on the `VoucherDraftMapper` boundary.
  - `VoucherEditorActions` construction (`seriesSupplier`): `{ (seriesComboBox.selectedItem as VoucherSeries)?.seriesCode ?: 'A' }` — `VoucherEditorActions`'s own signature (`Supplier<String>`) is unchanged.
- `applyReadOnlyState()`: `seriesComboBox.enabled = newSeriesButton.enabled = currentVoucher == null` (same rule the text field used).
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
  - A fresh fiscal year with no vouchers yet shows the combo pre-populated with a single seeded `A` series.
  - Creating a second series via `createNewSeries()`/the dialog makes it appear in the combo and become selected.
  - Switching fiscal year repopulates the combo with that year's series.
  - Combo and `+` button are disabled once an existing voucher is loaded, matching the current `seriesField` behavior.
