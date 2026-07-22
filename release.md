# Alipsa Accounting, Release History

## v1.6.1, In progress

### Improvements
- **Optional voucher auto-advance** — Voucher entry now includes a checked-by-default “New voucher after save” option. Clear it to remain on the saved voucher for printing or duplication.

### Bugfixes
- **Locale-change listener leak** — Seven panels (`FiscalYearPanel`, `ChartOfAccountsPanel`, `ReportPanel`, `MainFrame`, `VatPeriodPanel`, `VoucherPanel`, `SystemDocumentationPanel`) registered for locale-change and active-company notifications but never unregistered, leaking a listener on every panel construction. Panels now register/unregister via `addNotify`/`removeNotify` (or on shutdown for `MainFrame`), matching the pattern already used by `CompanyDialog` and `OverviewPanel`.
- Fixed the historic voucher balance calculation. It now subtracts the current voucher and all later vouchers from the fiscal-year ending balance, so “Saldo före” reflects the balance at that historical voucher. “Saldo efter” follows
  from that correctly.

## v1.6.0, 2026-07-22

### Bugfixes
- **Opening balances and SIE correctness** — The opening-balance editor is more robust when locale data is missing, and SIE imports now preserve the sign of credit-normal opening balances correctly. This previously only covered import; SIE export crashed for any fiscal year with opening balances, the imported `#UB` closing-balance cross-check produced incorrect warnings for credit-normal accounts (equity/liabilities), and the Balance Sheet report showed the wrong amount for those same accounts. All four are now fixed and covered by tests, including an end-to-end round trip that asserts the actual signed amount, not just record counts.

### Improvements
- **Local HTTP MCP integration** — The desktop app now exposes a token-protected MCP endpoint on localhost for AI clients. Stdio `--mode=mcp` configurations are no longer supported; configure the HTTP endpoint shown in Settings instead.
- **AI-assisted voucher drafts** — AI clients can inspect and fill an unsaved voucher draft, but only the user can save it from the desktop application.
- **Company accounting method** — Company settings can now specify cash or invoice accounting. Existing companies are safely migrated to cash accounting through database migration V25.
- **Restored working context** — The most recently selected company and fiscal year are restored on the next startup.
- **Smoother voucher entry** — New vouchers use the latest voucher date. Account and amount entry provide better counter-entry suggestions, and the save action has a clear icon.
- **Localized startup screen** — The startup message follows the application's selected language.
- **Better AI accounting context** — MCP clients can read the active company and fiscal year, account names, recent voucher lines, and user-approved accounting instructions for consistent recurring postings.
- **Voucher workflow improvements** — Unsaved drafts remain when browsing vouchers; first/last navigation and printing of drafts are available; corrections refer to the voucher number; and unsaved status is clearly marked.
- **Linux HiDPI polish** — The voucher save action is sized consistently with the other toolbar actions.

## v1.5.1, 2026-07-19

### Bugfixes
- Fixed the macOS CI warning in app/src/test/groovy/integration/se/alipsa/accounting/ui/VoucherPanelNavigationTest
- Fixed the Windows updater cleanup bug. The updater no longer deletes its own batch file while returning from call :main, which caused the “The batch file cannot be found” terminal after a successful update. The staged
  script remains and is overwritten on the next update.

### Improvements
- Improved voucher navigation performance
  - Voucher navigation now calculates all line-account balances in one database session, rather than running a separate query sequence per line.
  - Added voucher_line(account_id, voucher_id) index, which the balance aggregation needs.
  - Added migration V24 so existing Windows databases receive the index automatically.
  - add a session cache for per-voucher balance calculations
  - compute every line’s balance first and emits one fireTableDataChanged() event, rather than one event per row.
  - removed the redundant balance refresh and changed the row loader to retain a trailing blank row and update existing rows when the row count is unchanged.


## v1.5.0, 2026-07-18
- Re-enable splash screen
- Configurable data location — The database and app-data folder (attachments, reports, backups, SIE exports, logs) can now be pointed at a custom, persisted location from Settings, so the same user can run the app from multiple machines against a shared or mounted drive. Includes a guided move of existing data to the new location, and the app fails loudly with a clear message instead of silently falling back to a different database if the configured location becomes unreachable.
- Native file dialogs on Linux — All file choosers (SIE import/export, data location, chart-of-accounts import, backup/restore, and voucher attachments) now use FlatLaf's `SystemFileChooser`, which delegates to the native GTK file dialog on Linux. This makes GVfs-mounted remote locations, such as SFTP shares shown in Nemo, visible and selectable inside the app.
- **Faster voucher lookup and clearer layout** — Voucher navigation now includes a labelled Go to field that retrieves a specified voucher directly by its number. The voucher table gives more space to account descriptions and text while using compact Account, Debit, Credit, and balance columns.
- **Sidebar and System menu refinement** — Core accounting workflows are now reached from an icon-and-text sidebar. System functions moved to the top menu, with separate entries for System information, backup, restore, and the user manual.
- **GVFS SIE imports** — SIE files selected from Nemo SFTP/GVFS locations are copied through GIO for reliable import. The app remembers the remote import folder, retains the original SIE filename in import history, and accepts `.SE` files.
- **Backup visibility** — Backups saved to a user-selected folder are recorded and now appear consistently in both System information and the Overview's Last Backup card.
- Local install script — Added `localInstall.sh` for developers who want to build and install/update the app locally without creating a formal release. It builds the current platform package, extracts it to `~/.local/lib/alipsa-accounting`, and registers a desktop entry on Linux.
- Local update script — Added `localUpdate.sh` that updates an existing installation by auto-discovering it from desktop shortcuts or common install locations on Linux and macOS. Supports `--dir <path>` to skip discovery. Windows is not supported because the Windows release is installer-based.
- Updated dependencies:
  - Groovy 5.0.6 -> 5.0.7
  - JUnit 6.1.0 -> 6.1.1
  - FlatLaf 3.7.1 -> 3.7.2
  - Spotless Gradle plugin 8.7.0 -> 8.8.0
  - Gradle 9.6.0 -> 9.6.1

## v1.4.2, 2026-06-21
### Patch Release

This patch release fixes File and Help menu actions that could fail silently instead of opening their dialogs.

### Highlights

- **Menu actions fixed** — File and Help menu actions now open their dialogs again, including File → New company and Help → About. Menu callbacks now ignore Swing's action event argument before calling no-argument dialog methods.

### Downloads

| Platform                  | File                                  |
|---------------------------|---------------------------------------|
| Linux                     | `alipsa-accounting-1.4.2-linux.zip`   |
| Windows                   | `alipsa-accounting-1.4.2-windows.zip` |
| macOS                     | `alipsa-accounting-1.4.2-macos.zip`   |
| Universal updater archive | `app-1.4.2.zip`                       |

All artifacts are accompanied by SHA-256 checksum files and GPG signatures. Verify with:
```
gpg --verify <file>.asc <file>
```

Windows and macOS releases are not currently platform-code-signed/notarized, so those operating systems may still show their usual unsigned-application warnings.

## v1.4.1, 2026-06-21
### Patch Release

This patch release improves in-app update diagnostics and Linux update reliability after cases where an update could reach 100% and then appear stuck without leaving a useful updater log.

### Highlights

- **Persistent updater log** — The detached updater script now writes progress and errors to `updater.log` in the normal per-user app log directory on Linux, Windows, and macOS. The log records backup, file replacement, launcher configuration update, cleanup, restart, and failure paths.
- **Clearer update progress** — The update dialog now separates download, extraction, staging, and updater-launch phases, and shows the updater log path when handing off to the external updater script.
- **Safer apply phase** — Update installation work now runs from the background worker instead of the Swing completion callback, so the UI should no longer sit in an ambiguous applying state while the updater handoff is being prepared.
- **Linux subprocess workaround** — Linux launches now use `-Djdk.lang.Process.launchMechanism=VFORK`, addressing environments where the bundled runtime reports `posix_spawn failed, error: 13` when starting helper processes.
- **Updater test coverage** — The generated Linux/macOS and Windows updater scripts are now covered by tests, including a fake Unix install update that verifies JAR replacement, launcher config updates, script cleanup, and `updater.log` content without requiring a real release.

### Downloads

| Platform                  | File                                  |
|---------------------------|---------------------------------------|
| Linux                     | `alipsa-accounting-1.4.1-linux.zip`   |
| Windows                   | `alipsa-accounting-1.4.1-windows.zip` |
| macOS                     | `alipsa-accounting-1.4.1-macos.zip`   |
| Universal updater archive | `app-1.4.1.zip`                       |

All artifacts are accompanied by SHA-256 checksum files and GPG signatures. Verify with:
```
gpg --verify <file>.asc <file>
```

Windows and macOS releases are not currently platform-code-signed/notarized, so those operating systems may still show their usual unsigned-application warnings.

## v1.4.0, 2026-06-20
### Minor Release

This release improves voucher reuse, reorganizes settings and company-profile navigation, fixes Swing locale synchronization, and updates the Gradle build toolchain.

### Highlights

- **Duplicate vouchers as new drafts** — Existing vouchers now have a compact duplicate action that creates a new unsaved draft from the original series, description, and voucher lines. The draft uses the normal default date, previews the next voucher number, leaves attachments and audit history behind, and focuses the date picker so the user can choose a new booking date before saving.
- **Clearer settings organization** — The Settings tab is now grouped into Company profile, Application preferences, and Related configuration sections. Company profile editing uses the main company dialog, while quick links point users to VAT code assignment and VAT period reporting.
- **Simplified company settings code path** — The duplicate company settings dialog, service, and domain model were removed so company profile changes go through one maintained flow.
- **Workflow vs configuration guidance** — Main tabs now include tooltips that clarify which tabs are day-to-day workflows and which are configuration or system areas. The File menu labels were adjusted to match the new company profile terminology.
- **Safer company profile rendering** — Company profile summaries now escape HTML-sensitive characters before rendering, preventing malformed display when company data contains characters such as `<`, `>`, or `&`.
- **Runtime locale synchronization** — Changing application language now updates Swing's default component locale without changing the JVM default locale, so Swing widgets follow the selected UI language more consistently.
- **Build tooling update** — The project now builds with Gradle 9.6.0 and Spotless Gradle plugin 8.7.0.

### Downloads

| Platform                  | File                                  |
|---------------------------|---------------------------------------|
| Linux                     | `alipsa-accounting-1.4.0-linux.zip`   |
| Windows                   | `alipsa-accounting-1.4.0-windows.zip` |
| macOS                     | `alipsa-accounting-1.4.0-macos.zip`   |
| Universal updater archive | `app-1.4.0.zip`                       |

All artifacts are accompanied by SHA-256 checksum files and GPG signatures. Verify with:
```
gpg --verify <file>.asc <file>
```

Windows and macOS releases are not currently platform-code-signed/notarized, so those operating systems may still show their usual unsigned-application warnings.

## v1.3.0, 2026-05-20
### Minor Release

This release improves VAT code handling and VAT period workflows, makes amount entry and reports locale-aware, and fixes SIE import edge cases for deleted or corrected voucher rows.

### Highlights

- **VAT codes in the chart of accounts** — The chart of accounts now shows each account's VAT code and lets users set or clear compatible VAT codes directly. BAS imports assign standard VAT codes for common VAT accounts, backfill missing standard codes on re-import, and classify input VAT accounts such as `264x` as debit-side asset accounts.
- **More accurate VAT reporting** — VAT reporting now supports `REVERSE_CHARGE_EU_25` and handles EU reverse-charge output VAT per voucher so goods and services bases stay in the right VAT buckets. VAT transfer vouchers are excluded from VAT calculations more consistently when reports cover overlapping periods.
- **Safer VAT period processing** — VAT periods must now be reported and locked in chronological order, preventing later periods from being closed before earlier ones. The VAT period table supports selecting multiple periods for reporting or VAT transfer booking, with confirmation and clear partial-failure messages.
- **VAT period structure updates** — If a company's VAT periodicity changes, open VAT periods can be restructured without disturbing already reported or locked periods. This avoids stale monthly, quarterly, or annual period layouts after settings changes.
- **Locale-aware amounts** — Voucher entry, VAT previews, balances, and generated reports now format amounts with the active company's locale. Amount fields accept the local decimal separator and normalize comma/dot input during editing.
- **Voucher entry ergonomics** — Keyboard navigation in voucher rows is more predictable: account selection jumps to debit or credit based on the account's normal balance side, Tab/Enter advances through editable fields, and edited cell text is selected when entering by keyboard. The date picker accepts keyboard input.
- **Account lookup fixes** — The account lookup popup now hides when focus leaves the editor, no longer disappears before exact account-number input can be selected, and auto-selects a single exact account number match.
- **SIE import fixes** — SIE4 import now ignores `#BTRANS` and `#RTRANS` rows according to the SIE 4B model, skips vouchers that only contain deleted rows, and counts only importable `#TRANS` rows in previews. The import file chooser accepts uppercase `.SIE`, `.SI`, and `.SE` extensions.
- **Startup and status polish** — The application remembers the last active company across restarts, and status-bar messages automatically return to Ready after a short delay.

### Downloads

| Platform                  | File                                  |
|---------------------------|---------------------------------------|
| Linux                     | `alipsa-accounting-1.3.0-linux.zip`   |
| Windows                   | `alipsa-accounting-1.3.0-windows.zip` |
| macOS                     | `alipsa-accounting-1.3.0-macos.zip`   |
| Universal updater archive | `app-1.3.0.zip`                       |

All artifacts are accompanied by SHA-256 checksum files and GPG signatures. Verify with:
```
gpg --verify <file>.asc <file>
```

Windows and macOS releases are not currently platform-code-signed/notarized, so those operating systems may still show their usual unsigned-application warnings.

## v1.2.1, 2026-05-03
### Patch Release

This patch release improves update installation reliability, adds safer uninstall helpers, and tightens the smart SIE import replacement flow.

### Highlights

- **Fixed in-app updater for jpackage installs** — Updating from an installed app-image now resolves the correct launcher path, updates `AlipsaAccounting.cfg` to point at the new `app-<version>.jar`, and updates the packaged app-version metadata. The updater script is portable across Linux and macOS `sed`, handles Windows classpath separators, and the update dialog now reports installation failures instead of remaining stuck in the applying state.
- **Safer uninstall helpers** — Linux, macOS, and Windows releases now include uninstall or cleanup scripts that prompt before deleting the application installation directory and prompt separately before deleting accounting data. The default answer keeps both installation files and user data.
- **Trusted Linux desktop entries** — The Linux install script now marks the generated `.desktop` launcher executable and sets desktop trust metadata when `gio` is available, reducing “untrusted launcher” prompts after installation.
- **Smart SIE import refinements** — Replacement previews now use the same purge-summary collection path for blocked and unblocked replacements, and the unlock-and-replace confirmation shows the affected data counts before replacing a closed fiscal year without closing entries.
- **Startup verification fix** — The main window now constructs startup verification with the attachment service dependency required by the v1.2 attachment recovery checks.
- **Release housekeeping** — Local `releases/` and `issues/` directories are ignored by Git, and the project version is bumped to 1.2.1.

### Downloads

| Platform                  | File                                  |
|---------------------------|---------------------------------------|
| Linux                     | `alipsa-accounting-1.2.1-linux.zip`   |
| Windows                   | `alipsa-accounting-1.2.1-windows.zip` |
| macOS                     | `alipsa-accounting-1.2.1-macos.zip`   |
| Universal updater archive | `app-1.2.1.zip`                       |

All artifacts are accompanied by SHA-256 checksum files and GPG signatures. Verify with:
```
gpg --verify <file>.asc <file>
```

Windows and macOS releases are not currently platform-code-signed/notarized, so those operating systems may still show their usual unsigned-application warnings.

## v1.2.0, 2026-05-02
### Minor Release

This release adds permanent deletion of fiscal years and companies, makes attachment storage crash-safe, and simplifies the SIE import workflow.

### Highlights

- **Smart SIE import** — The separate Import and Replace buttons are merged into one. Clicking Import SIE runs a silent preview and picks the right path automatically: plain import when there is no collision, a confirmation with exact data counts when an existing fiscal year would be replaced, an unlock-and-replace offer when the fiscal year is closed but has no closing entries, and a clear error when closing entries prevent replacement.
- **Archive and unarchive companies** — A company can be archived from the File menu to hide it from normal views while keeping all its data intact. Archived companies can be restored at any time via File → Unarchive company. Addresses issue #45.
- **Delete company** — Once all fiscal years belonging to a company have been deleted, the company itself can be permanently removed via File → Delete company. All associated data is purged in one step.
- **Delete fiscal year** — Fiscal years past the 7-year legal retention period can be permanently deleted from the Fiscal Years tab. A preview shows exactly what will be removed: vouchers, attachments, report archives, VAT periods, opening balances, and audit log entries. Attachment and report files stored on disk are deleted as part of the operation; any file that cannot be deleted is listed in the result for manual cleanup.
- **Crash-safe attachment storage** — Attachments are now written using a two-phase copy-then-confirm approach. If the application is interrupted mid-copy, the incomplete file is detected on next startup and either recovered or reported as a warning rather than silently left in an inconsistent state.

### Downloads

| Platform                  | File                                  |
|---------------------------|---------------------------------------|
| Linux                     | `alipsa-accounting-1.2.0-linux.zip`   |
| Windows                   | `alipsa-accounting-1.2.0-windows.zip` |
| macOS                     | `alipsa-accounting-1.2.0-macos.zip`   |
| Universal updater archive | `app-1.2.0.zip`                       |

All artifacts are accompanied by SHA-256 checksum files and GPG signatures. Verify with:
```
gpg --verify <file>.asc <file>
```

Windows and macOS releases are not currently platform-code-signed/notarized, so those operating systems may still show their usual unsigned-application warnings.

## v1.1.1, 2026-04-26
### Patch Release

This patch release improves multi-company setup and import workflows, fixes chart-of-accounts filtering, and documents the MCP/LLM client model added in v1.1.0.

### Highlights

- **Chart-of-accounts filtering** — Changing the account class filter or active-only checkbox now immediately reloads the account list.
- **Company settings placement** — The company edit action moved from the global Settings tab to the Overview tab, next to the active company summary. The Settings tab now focuses on global preferences such as language, theme, and update checks.
- **Safer company creation/editing** — New and edited companies now choose locale and currency from dropdowns instead of free-text fields, reducing invalid locale tags and misspelled currency codes while preserving existing out-of-list values.
- **SIE import to new company** — When SIE import creates a new company, the main company dropdown refreshes immediately and selects the imported company without requiring an application restart.
- **Documentation updates** — README and the user manual now describe the MCP mode, LLM skill setup, current release artifacts, and deferred roadmap items more clearly.

### Downloads

| Platform                  | File                                  |
|---------------------------|---------------------------------------|
| Linux                     | `alipsa-accounting-1.1.1-linux.zip`   |
| Windows                   | `alipsa-accounting-1.1.1-windows.zip` |
| macOS                     | `alipsa-accounting-1.1.1-macos.zip`   |
| Universal updater archive | `app-1.1.1.zip`                       |

All artifacts are accompanied by SHA-256 checksum files and GPG signatures. Verify with:
```
gpg --verify <file>.asc <file>
```

Windows and macOS releases are not currently platform-code-signed/notarized, so those operating systems may still show their usual unsigned-application warnings.

## v1.1.0, 2026-04-26
### Minor Release

This release adds headless MCP support for AI-assisted bookkeeping workflows, tightens voucher and fiscal-year safety rules, and improves release packaging for automatic updates.

### Highlights

- **AI/MCP bookkeeping tools** — Added an MCP server mode with tools for company lookup, fiscal years, accounts, vouchers, trial balance, general ledger, VAT periods, VAT reports, VAT transfer booking, year-end preview/closing, SIE import/export, and SIE import job history.
- **Accounting skill distribution** — Releases now include `skill/accounting-mcp.md` so Claude Code and Codex can use guided accounting workflows after the skill is linked or copied into the client's skill directory.
- **Immutable posted vouchers** — Posted vouchers are now append-only. Corrections are handled with reversing correction vouchers instead of direct edits, cancellation, or deletion.
- **Fiscal-year closing safety** — Year closing now locks the fiscal year, with explicit support for unlocking through an audit-logged action. MCP year-end closing uses preview tokens so the close operation matches the previewed state.
- **Safer SIE workflows** — MCP SIE import now supports preview, blocking issue reporting, duplicate detection, replacement purge summaries, import tokens, and drift protection before replacing an existing fiscal year. SIE export uses timestamped default filenames and refuses to overwrite files unless explicitly confirmed.
- **Improved release artifacts and updater support** — Windows and macOS releases are now packaged as platform zip files alongside Linux, and the generic `app-<version>.zip` plus checksum is published for the built-in updater.
- **Distribution fixes** — Windows release artifacts now include the installer and skill file in a zip that the release workflow can publish consistently. macOS packaging now follows the same release-zip lifecycle.
- **Privacy and documentation updates** — The privacy policy and README now describe MCP mode, local skill installation, current artifact naming, and verification/signing expectations.

### Downloads

| Platform                  | File                                  |
|---------------------------|---------------------------------------|
| Linux                     | `alipsa-accounting-1.1.0-linux.zip`   |
| Windows                   | `alipsa-accounting-1.1.0-windows.zip` |
| macOS                     | `alipsa-accounting-1.1.0-macos.zip`   |
| Universal updater archive | `app-1.1.0.zip`                       |

All artifacts are accompanied by SHA-256 checksum files and GPG signatures. Verify with:
```
gpg --verify <file>.asc <file>
```

Windows and macOS releases are not currently platform-code-signed/notarized, so those operating systems may still show their usual unsigned-application warnings.

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
