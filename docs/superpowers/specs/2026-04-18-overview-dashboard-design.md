# Overview Dashboard Design

**Date:** 2026-04-18  
**Status:** Approved

## What we're building

Replace the placeholder Overview tab with a minimal status dashboard. The dashboard answers one question: "Is everything in order?" It shows six data points arranged as a company/fiscal-year header strip above a 2×2 stat card grid.

## Data points

| Item | Source | Notes |
|---|---|---|
| Company name + org number | `ActiveCompanyManager.activeCompany` | — |
| Current fiscal year name, date range, open/closed | `ActiveCompanyManager.fiscalYear` | — |
| Voucher count | `VoucherService.countVouchers(companyId, fiscalYearId)` | New `select count(*)` method |
| Accounting periods locked / total | `AccountingPeriodService.listPeriods(fiscalYearId)` | Count locked in-panel |
| Last backup date/time | `BackupService.listBackups(1).find()` | — |
| Integrity status | `StartupVerificationService.verify()` | Run once on first load; cached in panel |

## Layout

**Header strip** (full width, single card):
- Left half: company name (large) + org number (small grey)
- Vertical separator
- Right half: fiscal year name (large) + date range and open/closed status (small grey)

**2×2 stat grid** below the header:
- Top-left: Vouchers
- Top-right: Accounting Periods (locked / total)
- Bottom-left: Last Backup
- Bottom-right: Integrity

Swing implementation: `GridBagLayout` for the header strip; `GridLayout(2, 2)` for the stat cards. Each stat card is a `JPanel` with an `EtchedBorder`, a small grey label for the title, and a larger bold label for the value.

## Color rules

| Condition | Display |
|---|---|
| Backup age > 60 days AND vouchers created after last backup | Date and age label shown in red |
| Never backed up | "Never" shown in grey (informational, not an error) |
| Integrity check failed | Value shown in red with ✗ prefix |
| Fiscal year closed | "Closed" shown in grey (informational) |
| No active fiscal year | All stat card values show "—" |
| All clear | All values in default colour |

The 60-day threshold exists so that creating a single voucher does not immediately trigger a warning. Both conditions must be true (age > 60 days AND unprotected vouchers) before the backup card turns red.

## Service additions

Two new methods, both in existing service classes:

**`VoucherService.countVouchers(long companyId, long fiscalYearId): int`**  
`select count(*) from voucher where fiscal_year_id = ? and status in ('ACTIVE', 'CORRECTION')` — joined to company via fiscal_year.

**`VoucherService.hasVouchersCreatedAfter(long companyId, LocalDateTime since): boolean`**  
`select count(*) from voucher where ... and created_at > ?` — used for the backup warning rule.

## Refresh behaviour

- **On company change**: `propertyChange` on `ActiveCompanyManager.COMPANY_ID_PROPERTY` triggers full reload.
- **On tab selected**: `ChangeListener` on the `JTabbedPane` reloads when the Overview tab becomes visible. Catches vouchers or backups created while on another tab.
- **Integrity check**: runs once on first panel load; result cached in the panel for the session. Does not re-run on tab switch (hash chain verification is expensive).

## New class

**`OverviewPanel`** (`ui/OverviewPanel.groovy`)  
- `final class OverviewPanel extends JPanel implements PropertyChangeListener`
- Constructor args: `VoucherService`, `AccountingPeriodService`, `BackupService`, `StartupVerificationService`, `ActiveCompanyManager`
- Replaces `buildOverviewPanel()` in `MainFrame`

No new service class is needed.

## i18n

New keys in `messages.properties` and `messages_sv.properties` under the `overviewPanel.*` namespace covering all labels, stat titles, and status values (Open/Closed, OK/Failed, Never, days-ago formatting).

## Age display format

Backup age is shown as a secondary label beneath the date:
- Same day → "today"
- 1 day → "yesterday"
- 2–N days → "N days ago"
- No backup → label omitted

## Out of scope

- Quick-action buttons
- Charts or graphs
- Auto-refresh timer
- Drill-down navigation from dashboard cards
