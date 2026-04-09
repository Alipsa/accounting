# Repository Guidelines

## Project Structure & Module Organization
This repository is a Gradle-based desktop accounting application with one main module: `app/`.

- `app/src/main/groovy/se/alipsa/accounting/`: application code.
- `app/src/main/groovy/se/alipsa/accounting/service/`: database and business services.
- `app/src/main/groovy/se/alipsa/accounting/ui/`: Swing UI panels and dialogs.
- `app/src/main/groovy/se/alipsa/accounting/domain/`: domain models.
- `app/src/main/resources/db/`: schema, indexes, and SQL migrations.
- `app/src/test/groovy/`: tests, organized into `unit`, `integration`, and `acceptance`.
- `config/codenarc/` and `config/groovy/`: static analysis and compiler config.
- `req/`: roadmap and implementation task documents.

## Build, Test, and Development Commands
- `./gradlew build`: full validation, including compilation, tests, Spotless, and CodeNarc.
- `./gradlew test`: run all tests.
- `./gradlew run`: start the desktop application locally.
- `./gradlew codenarcMain`: run static analysis on production code.
- `./gradlew spotlessCheck`: verify formatting.

Run commands from the repository root.

## Coding Style & Naming Conventions
- Use 2-space indentation in Groovy and Gradle files.
- Keep SQL readable and manually formatted; do not reflow migrations.
- Prefer `@CompileStatic` unless a UI class needs dynamic SwingBuilder behavior.
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
- All schema changes must go through migrations in `app/src/main/resources/db/migrations/`.
- Do not edit generated build output under `app/build/`.

## Agent-Specific Notes
- For Swing work, preserve the existing desktop patterns in `ui/`; prefer small, targeted dialog/panel changes over broad rewrites.
- Verify UI-related changes with at least `./gradlew build`, even when behavior is mostly visual.
- For SQL work, update migrations first and keep `schema.sql` readable and aligned with the intended bootstrap state.
- Do not let generic formatters reflow `.sql` files; review SQL diffs manually before finishing.
