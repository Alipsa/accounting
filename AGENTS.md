# Repository Guidelines

## Project Structure & Module Organization
This repository is a Gradle-based desktop accounting application with one main module: `app/`.

- `app/src/main/groovy/se/alipsa/accounting/`: application code.
- `app/src/main/groovy/se/alipsa/accounting/service/`: database and business services.
- `app/src/main/groovy/se/alipsa/accounting/ui/`: Swing UI panels and dialogs.
- `app/src/main/groovy/se/alipsa/accounting/domain/`: domain models.
- `app/src/main/groovy/se/alipsa/accounting/support/`: shared support utilities.
- `app/src/main/groovy/se/alipsa/accounting/mcp/`: local MCP server and tool dispatcher.
- `app/src/main/resources/db/`: schema, indexes, and SQL migrations.
- `app/src/main/resources/reports/`: FreeMarker templates for generated reports.
- `app/src/main/resources/docs/`, `i18n/`, and `icons/`: bundled manual, translations, and image assets.
- `app/src/test/groovy/`: tests, organized into `unit`, `integration`, and `acceptance`.
- `config/codenarc/` and `config/groovy/`: static analysis and compiler config.
- `packaging/`: platform packaging resources for `jpackage` releases.
- `skill/`: bundled MCP skill documentation included in distributions.
- `docs/`, `specs/`, `issues/`, and `req/`: design notes, task documents, and roadmap material.

## Build, Test, and Development Commands
- Java 21 is the required Gradle toolchain.
- `./gradlew build`: full validation, including compilation, tests, Spotless, and CodeNarc.
- `./gradlew test`: run all tests.
- `./gradlew run`: start the desktop application locally.
- `./gradlew codenarcMain`: run static analysis on production code.
- `./gradlew spotlessCheck`: verify formatting.
- `./gradlew :app:packageCurrentPlatformRelease`: build the release package for the current platform with `jpackage`.
- `./gradlew :app:verifyCurrentPlatformRelease`: package and smoke-test the current platform launcher.

Run commands from the repository root.

`.github/workflows/ci.yml` runs `./gradlew build` on every PR and push to `main`; `main` requires this check to pass before merging.

## Coding Style & Naming Conventions
- Use 2-space indentation in Groovy and Gradle files.
- Keep SQL readable and manually formatted; do not reflow migrations.
- `@CompileStatic` is enforced globally via `config/groovy/compileStatic.groovy`; per-class annotations are not needed. Only add explicit `@CompileStatic` if a class must opt out and back in selectively.
- Class names use `PascalCase`; methods and fields use `camelCase`.
- Service classes end with `Service`, dialogs with `Dialog`, panels with `Panel`.
- Use `apply_patch`-style minimal edits and avoid unrelated formatting churn.

Formatting and linting are enforced by Spotless and CodeNarc. Fix warnings instead of suppressing them unless the rule is clearly inappropriate for this codebase.

## Testing Guidelines
- Test framework: JUnit 6 with `groovier-junit`.
- Place tests under `app/src/test/groovy/{unit|integration|acceptance}`.
- Name test classes `*Test.groovy`.
- Add integration tests for schema changes, migrations, and service-layer business rules.
- Before opening a PR, run `./gradlew build`.

## Commit & Pull Request Guidelines
Recent history favors short, descriptive commit messages, often in Swedish imperative form, for example: `fixade formattering` or `ändrade till två mellanslags indentering`.

- Keep commits focused on one change set.
- Describe user-visible effects and technical risk in the PR body.
- Link the relevant task/roadmap item from `req/` when applicable.
- Include screenshots for Swing UI changes.
- Note any schema or migration impact explicitly.

## Security & Configuration Tips
- H2 is embedded-only; do not introduce networked DB modes.
- All schema changes must go through migrations in `app/src/main/resources/db/migrations/`, and new migrations must be registered in `DatabaseService.MIGRATIONS`.
- Do not edit generated build output under `app/build/`, root `build/`, `.gradle/`, `.gradle-user/`, `dist/`, or `releases/`.

## Data Integrity & Corrections
`audit_log` is an append-only, SHA-256 hash-chained ledger (see `AuditLogService`): each row's `entry_hash` commits to its own content plus the previous row's hash, so altering anything after the fact breaks the chain in a way `validateIntegrity()` is specifically designed to catch. This is intentional tamper-evidence, not an implementation detail to work around.

- **Never directly `UPDATE`/`DELETE` rows in `audit_log`, `voucher`, `voucher_line`, `opening_balance`, or other ledger tables via raw SQL to fix bad data** - not even when the fix is obviously correct and the user asks for it directly. This is exactly what caused a real incident: a prior session patched incorrect opening balances straight in the database, which silently broke `entry_hash` for the affected rows without anyone noticing until much later.
- Data corrections must go through the application's own domain operations, not raw SQL, so the correction itself produces a new, properly-chained audit entry describing what changed and why. `VoucherService`'s correction-voucher pattern (`CORRECTION_VOUCHER`, `recordCorrectionVoucher`) is the model to follow: never edit a wrong voucher in place, post a new one that references and reverses it. Extend that same append-only pattern to other data types rather than reaching for an `UPDATE` statement.
- If you discover a hash-chain integrity violation - from a bug, a previous manual patch, or anything else - do not "fix" it by rewriting `entry_hash`/`previous_hash` on the broken row, and do not call `AuditLogService.rebuildIntegrityChain`/`repairIntegrityForAllCompanies` casually. Those exist solely as one-time, migration-triggered repair tools for specific historical bugs (see `V27__audit_log_decouple_references.sql` and `DatabaseService.initialize()`), gated to run exactly once, never as general-purpose "make the warning go away" utilities - doing so would silently mask real tampering going forward. Use `AuditLogService.recordIntegrityRemediation(companyId, auditLogId, reason)` instead: it appends a new, normally-chained entry documenting *why* a specific row's mismatch is a known, explained anomaly, which moves it from `validateIntegrity()`'s blocking critical list to `listDocumentedExceptions()` - visible and no longer blocking, without ever touching the original row.
- If you are unsure whether something needs a proper code fix versus a one-off manual correction, stop and ask the user before running SQL against a live database - do not decide unilaterally.

## Agent-Specific Notes
- Always ask before creating a new git branch.
- Never push directly to `main` unless the user explicitly says to push `main`, or you ask for and receive confirmation immediately before pushing.
- After finishing an implementation or code fix, always run `./gradlew spotlessApply` to auto-format before committing, then inspect the diff because Spotless can also touch Markdown files.
- After implementing a task, run CodeNarc for the modified production classes before the full build (normally `./gradlew codenarcMain`) so newly introduced static-analysis regressions are caught early.
- For Swing work, preserve the existing desktop patterns in `ui/`; prefer small, targeted dialog/panel changes over broad rewrites.
- Verify UI-related changes with at least `./gradlew build`, even when behavior is mostly visual.
- For SQL work, update migrations first and keep `schema.sql` readable and aligned with the intended bootstrap state.
- Do not let generic formatters reflow `.sql` files; review SQL diffs manually before finishing.
