# Alipsa Accounting, Release History

## v1.0.1, 2026-04-20
### Patch Release

This patch release improves startup, makes SIE import safer, and fixes report and balance issues discovered after v1.0.0.

### Highlights

- **Safer SIE import** — The app now warns you when an SIE file appears to belong to another company and lets you cancel, continue, or create a new company from the file and import there instead.
- **More reliable reports and balances** — Fixed issues affecting imported opening and closing balances, balance sheet calculations, and income statement presentation.
- **Startup splash screen** — You now get a startup screen while the application is loading.
- **Optional update checks** — Automatic update checks at startup can now be turned off in Settings.
- **Improved stability** — Fixed cases where import logs, audit details, and archived report data could fail to load correctly.

## v1.0.0, 2026-04-19
### Initial Release

First release of Alipsa Accounting — a desktop bookkeeping application for small Swedish businesses.

### Features

- **Voucher entry** — Create, edit, correct, and cancel journal entries with keyboard navigation and account lookup
- **Chart of accounts** — BAS chart of accounts with import, search, and filtering
- **Opening balances** — Enter and manage opening balances per account
- **Accounting periods** — Period locking and fiscal year closing
- **VAT reporting** — Quarterly VAT periods with automatic transfer journal entries
- **Financial reports** — Balance sheet (ÅRL/K2/K3), income statement, general ledger, transaction report, and voucher list; export to Excel and PDF
- **SIE import/export** — Import and export accounting data in SIE4 format
- **Attachments** — Attach documents to vouchers
- **Audit trail** — Tamper-evident hash chain covering all vouchers and key events
- **Backup and restore** — One-click database backup and restore
- **Status dashboard** — Overview of company status, voucher count, backup freshness, and data integrity
- **Multiple companies** — Manage separate books for more than one company
- **Swedish and English UI** — Switch language in settings
- **Auto-update check** — Notifies you when a new version is available

### Downloads

| Platform | File |
|----------|------|
| Linux | `alipsa-accounting-1.0.0-linux.zip` |
| Windows | `AlipsaAccounting-1.0.0.exe` |
| macOS | `AlipsaAccounting-macos.zip` |
| Universal (JVM) | `app-1.0.0.zip` |

All artifacts are signed with GPG. Verify with:
```
gpg --verify <file>.asc <file>
```

Windows and macOS releases are not currently platform-code-signed/notarized, so those operating systems may still show their usual unsigned-application warnings.
