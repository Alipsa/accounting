# Voucher UI Redesign

## Goal

Replace the two-component voucher UI (VoucherListPanel + VoucherEditor dialog) with a single inline VoucherPanel that shows one voucher at a time with direct table editing, bidirectional account lookup, calculated balance columns, and sequential navigation. Remove the DRAFT/BOOKED distinction and hash chain. Move fiscal year and company selectors to the bottom bar.

## Architecture

The voucher tab becomes a single `VoucherPanel` embedded in MainFrame. Vouchers are always editable until their accounting period is locked (by VAT report or bokslut). No explicit "book" action exists — vouchers are persisted on save with status `ACTIVE`. The bottom bar becomes the application-wide context for company and fiscal year selection.

## Layout

### VoucherPanel Structure

Three areas stacked vertically:

**1. Header bar** — voucher-level fields in a single row:
- Verifikationsnummer (read-only label, e.g. "A-3")
- Datum (date picker, required — must fall within the active fiscal year)
- Beskrivning (text field, required, max 500 chars)
- Korrigerar (read-only label, visible only for correction vouchers, shows original voucher number)

**2. Navigation/action toolbar:**
- Prev button — navigate to previous voucher in the fiscal year
- Next button — navigate to next voucher
- Jump-to field — type a voucher number (e.g. "A-3") and press Enter to navigate directly
- New voucher button (+) — create a blank voucher
- Save button (save icon) — persist the voucher and advance to a new blank voucher
- Create correction button — create a reversing voucher for the current one (only for vouchers in locked periods)
- Void button — cancel the current voucher (only when period is unlocked)

**3. Main area** — tabbed pane:
- **Rader / Lines tab** — the editable line table (see Line Table section) with a totals row below
- **Bilagor / Attachments tab** — unchanged from current VoucherEditor
- **Historik / History tab** — unchanged from current VoucherEditor

### Bottom Bar (MainFrame level)

Replaces the current status-only bar. Layout left to right:
- **Company dropdown** (moved from top toolbar)
- **Fiscal year dropdown** (moved from voucher panel; now application-wide)
- **Status message** (right-aligned)

Changing company reloads the fiscal year list. Changing fiscal year affects VoucherPanel, ChartOfAccountsPanel, VAT periods, and reports.

### Top Toolbar (after relocation)

- Language selector
- Theme selector
- Edit company settings button

## Line Table

The editable table in the Lines tab. All column headers are translated via i18n.

| Column | Swedish | English | Editable | Description |
|--------|---------|---------|----------|-------------|
| Konto | Konto | Account | Yes | 4-digit account number. Typing a valid number and pressing Enter or leaving the cell auto-fills Kontobeskrivning. Invalid numbers are highlighted. |
| Kontobeskrivning | Kontobeskrivning | Account description | Yes | Free-text search. Typing a fragment shows a popup of matching accounts. Selecting one auto-fills Konto. |
| Debet | Debet | Debit | Yes | Decimal amount (scale 2). Entering a value clears Kredit on the same row. |
| Kredit | Kredit | Credit | Yes | Decimal amount (scale 2). Entering a value clears Debet on the same row. |
| Text | Text | Text | Yes | Optional line-level description (max 500 chars). |
| Kontosaldo fore | Kontosaldo före | Account balance before | No | Calculated: opening balance + net of all vouchers for this account in the fiscal year, excluding the current voucher. |
| Kontosaldo efter | Kontosaldo efter | Account balance after | No | Calculated: "före" + this line's debit/credit contribution. |

**Totals row:** Displayed below the table. Shows summed Debet, summed Kredit, and the difference.

**Adding rows:** An empty row is always present at the bottom of the table. Filling in Konto or Kontobeskrivning creates the row and adds a new empty row below.

**Removing rows:** A "Remove line" button removes the currently selected row.

**Read-only mode:** When the voucher's accounting period is locked, all cells become non-editable. Save, void, and remove-line controls are disabled. A status indicator shows why the voucher is locked.

## Account Lookup Popup

Triggered when typing in the Kontobeskrivning cell, after a ~200ms debounce:

- Queries `AccountService.searchAccounts()` with the typed fragment against both account name and account number, filtered to active accounts only
- Shows results formatted as `"{accountNumber} -- {accountName}"` (e.g. "1930 -- Bankgiro")
- Max 10 visible rows, scrollable if more matches
- Keyboard navigation: arrow keys to move, Enter to select, Escape to dismiss
- Selecting an entry fills both Konto and Kontobeskrivning and triggers balance calculation
- If only one match remains, Enter selects it directly
- If no matches, the popup shows a "no matches" hint

When typing directly in the Konto cell:
- On focus loss or Enter, the account number is validated against the chart of accounts
- If valid, Kontobeskrivning auto-fills with the account name
- If invalid, the cell is highlighted with an error indication

## Kontosaldo Calculation

Both balance columns are computed at display time, never stored in the database.

**Kontosaldo fore (balance before):**
- `opening_balance.amount` for the account in the current fiscal year (0 if none)
- Plus the net of all debit/credit on that account from all vouchers in the fiscal year, excluding the current voucher
- For debit-normal accounts (ASSET, EXPENSE): net = sum(debit) - sum(credit)
- For credit-normal accounts (LIABILITY, EQUITY, INCOME): net = sum(credit) - sum(debit)

**Kontosaldo efter (balance after):**
- Kontosaldo fore + this line's contribution (debit adds for debit-normal, credit adds for credit-normal)

**Service method:** `AccountService.calculateAccountBalance(long companyId, long fiscalYearId, String accountNumber, Long excludeVoucherId)` returns the "fore" value with a single SQL query joining `opening_balance` and `voucher_line`.

**Recalculation triggers:**
- Account selected or changed on a row
- Voucher loaded during navigation
- Cached per account within a voucher editing session; recalculated when account or amounts change

## Voucher Lifecycle Changes

### Status Model

`VoucherStatus` enum changes:
- Remove: `DRAFT`, `BOOKED`
- Add: `ACTIVE`
- Keep: `CANCELLED`, `CORRECTION`

New vouchers are created with status `ACTIVE`. An `ACTIVE` voucher is freely editable as long as its accounting period is not locked.

### Hash Chain Removal

The following become unused and are removed:
- `voucher.previous_hash` column
- `voucher.content_hash` column
- `voucher.booked_at` column
- `voucher_chain_head` table
- Integrity validation methods in `VoucherService`

### Saving Flow

1. User fills in header fields (date, description) and lines
2. Presses save — voucher is persisted with status `ACTIVE`
3. Running number and voucher number are assigned on first save
4. A new blank voucher is presented immediately
5. Navigating back to an existing voucher and editing it updates in place (as long as the period is unlocked)

### Correction Flow

Unchanged in principle, adapted to new statuses:
- Creates a reversing voucher with debit/credit swapped
- New voucher gets status `CORRECTION` and references the original via `original_voucher_id`
- Only available for vouchers in locked periods (since unlocked vouchers can be edited directly)

### Period Locking and Audit Integrity

- The accounting period is the single source of truth for "is this voucher editable?". An `ACTIVE` voucher remains editable as long as `accounting_period.locked = false` for the period containing its date.
- After the VAT transfer voucher for a VAT period is booked successfully, the UI prompts the user to lock all accounting periods up to and including the VAT period's end date. Locking is irreversible through the UI.
- Because edit surface is bounded by the unlocked window, a hash chain on voucher content is no longer required. Audit integrity is provided by:
  - The audit log (hash-chained), which records `CREATE_VOUCHER`, `UPDATE_VOUCHER`, `CANCEL_VOUCHER`, `CORRECTION_VOUCHER` events including the full line content (account, debit, credit, text) for before/after traceability.
  - Period locking, which prevents further edits once VAT is reported or bokslut is done.

### Fiscal-year closing and separate series

Bokslut runs after all regular accounting periods have been locked (either through successive VAT reports or an explicit lock at year end). The result-allocation voucher therefore cannot be booked in any of the ordinary series — those series only accept dates whose accounting period is still unlocked. Year-end closing uses a dedicated series (`ClosingService.createVoucherBypassLock`) so the closing voucher can be registered against a date inside a locked period without reopening it.

### Database Migration

A new migration:
- Changes existing `DRAFT` rows to `ACTIVE`
- Changes existing `BOOKED` rows to `ACTIVE`
- Drops `previous_hash`, `content_hash`, `booked_at` columns from `voucher`
- Drops `voucher_chain_head` table

## Impact on Other Panels

### FiscalYearPanel
- No longer has its own fiscal year selection concern — it manages fiscal years for the current company
- The active fiscal year is always the one selected in the bottom bar

### ChartOfAccountsPanel
- No change needed; already uses company context

### MainFrame
- Company selector moves from top toolbar to bottom bar
- Fiscal year selector added to bottom bar
- VoucherListPanel replaced by VoucherPanel in the Vouchers tab
- VoucherEditor dialog removed

## Removed Features

- `VoucherListPanel` (replaced by VoucherPanel navigation)
- `VoucherEditor` dialog (replaced by inline editing)
- Refresh button
- Status column in voucher display
- Hash column in voucher display
- DRAFT/BOOKED status distinction
- Hash chain (previous_hash, content_hash, voucher_chain_head)
- booked_at timestamp
- Fiscal year dropdown in voucher area
- Company selector in top toolbar

## Added Features

- `VoucherPanel` with inline editing and navigation
- Kontobeskrivning column with search popup
- Kontosaldo fore / Kontosaldo efter calculated columns
- Prev/next/jump-to navigation
- Auto-advance to new blank voucher after save
- Fiscal year + company in bottom bar
- `AccountService.calculateAccountBalance()` service method
- `VoucherStatus.ACTIVE` replacing DRAFT and BOOKED
- Database migration for status and schema changes

## i18n

All new UI labels, column headers, buttons, and messages must have entries in both `messages.properties` (English) and `messages_sv.properties` (Swedish). Existing voucher-related keys that reference removed concepts (e.g. draft, book, hash) are removed or updated.

## Testing

- Unit tests for `VoucherStatus.ACTIVE` and removal of DRAFT/BOOKED
- Unit tests for `calculateAccountBalance` with various scenarios (no data, opening balance only, multiple vouchers, exclusion of current voucher)
- Integration tests for the new migration (DRAFT->ACTIVE, BOOKED->ACTIVE, hash column drops)
- Integration tests for saving and updating vouchers with the new lifecycle
- Integration tests for period-locking preventing edits
