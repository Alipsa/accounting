# AI Assistant Launcher — Design Spec

**Date:** 2026-07-23

## Context

The app already bundles an MCP skill file (`skill/accounting-mcp.md`) and runs an embedded, loopback-only HTTP MCP server (`LoopbackMcpServer`, bearer-token auth via `UserPreferencesService`). Today, wiring an AI CLI (Claude Code, Codex, Kimi, Mistral Vibe) up to this server is entirely manual: the user edits the CLI's own config by hand and symlinks the skill file into a client-specific directory, following README instructions.

Two problems with today's manual setup:
1. It's tedious and error-prone (four different config formats, symlink commands per OS).
2. If the MCP server is registered in the CLI's **global** config, every unrelated session of that CLI (in any other project, any time the accounting app isn't running) tries to reach `127.0.0.1:48652` and fails, showing a confusing connection error to the user.

This spec adds a "Launch AI Assistant" feature to the Settings UI: pick a client, click a button, and the app writes a **project-scoped** config into a dedicated workspace directory, then opens the user's terminal with that CLI running in that directory — so the accounting MCP registration is only ever visible in that one context.

---

## Scope

- New workspace concept under the app's existing data home (`AppPaths`).
- New `domain/AiClient.groovy` enum (plain value type, no Swing/service dependency — same rationale as other domain enums like `VoucherStatus`).
- New `service/AiWorkspaceService.groovy` (config/instructions writers, detection) and `service/AiAssistantLauncher.groovy` (terminal spawn) — pure file I/O and process management, no Swing dependency, so `service/` rather than `ui/`.
- `McpSettingsSection.groovy` UI additions: client dropdown, Launch button, per-client binary-path field, shared terminal-command field, each with a "Detect" button.
- New `UserPreferencesService` keys for the four binary-path overrides and the terminal-command override.
- I18n keys (sv + default).
- Unit tests for config writers and PATH/terminal detection logic.

Out of scope: embedding a terminal inside the Swing UI; shelling out to each vendor's own `mcp add` CLI subcommand (rejected — see Decision below); auto-installing any of the four CLI tools themselves.

---

## Decision: write config files directly, don't shell out to vendor CLIs

Considered shelling out to `claude mcp add`, `codex mcp add`, `kimi mcp add` (Vibe has no such subcommand at all). Rejected because:
- Inconsistent across clients (Vibe has none; the other three have different flag dialects and versions may drift).
- Doesn't cleanly solve Codex's token handling: `codex mcp add` only accepts a bearer token via an env-var *name*, resolved at connect time — awkward if we're not also the ones launching the process.

Instead, the app writes each client's config file directly, in a workspace directory it fully owns, and spawns the CLI itself — which also means it fully controls the child process's environment (solving the Codex token problem: set `ACCOUNTING_MCP_TOKEN` only for that spawned process, never touch the user's shell profile).

---

## Workspace layout

New directory: `AppPaths.applicationHome().resolve('ai-workspace')` (mirrors the existing `dataDirectory()`/`attachmentsDirectory()` pattern in `AppPaths.groovy`; add `aiWorkspaceDirectory()` alongside them and register it in `ensureDirectoryStructure()`).

This directory is **fully app-managed** — nothing else should write user content there, so files inside it are always overwritten on each launch to keep the endpoint/token current. It contains, per client:

| Client | Config file (relative to workspace) | Format | Instructions file |
|---|---|---|---|
| Claude Code | `.mcp.json` | JSON | `.claude/skills/accounting/accounting-mcp.md` (symlink to bundled skill file) |
| Codex | `.codex/config.toml` | TOML | `AGENTS.md` (copy of skill content) |
| Kimi | `.kimi-code/mcp.json` | JSON | `AGENTS.md` (copy of skill content — user confirmed Kimi supports this) |
| Vibe | `.vibe/config.toml` | TOML | `AGENTS.md` (best-effort copy; convention unconfirmed for Vibe) |

Claude Code's config carries a literal `Authorization: Bearer <token>` header; same for Kimi and Vibe. Codex's config references an env-var name (`ACCOUNTING_MCP_TOKEN`) that the launcher sets only on the spawned process's environment.

TOML is written by hand with simple string formatting (the schema needed is a small fixed `[mcp_servers.accounting]` table) — no new TOML library dependency.

---

## Components

### `AiClient` (new enum, `domain/AiClient.groovy`)
One entry per client (`CLAUDE, CODEX, KIMI, VIBE`), each knowing:
- default binary name (`claude`, `codex`, `kimi`, `vibe`)
- its config file's relative path + a small writer for its format
- its instructions-file relative path and whether that's a symlink (Claude) or a content copy (others)
- display name for the dropdown (I18n key)

### `AiWorkspaceService` (new, `service/AiWorkspaceService.groovy`)
- `ensureWorkspace()` — creates the directory if missing.
- `refreshClientFiles(AiClient client, String endpoint, String token)` — (re)writes that client's config + instructions file, always overwriting (workspace is app-managed).
- `detectBinaryPath(AiClient client)` — resolves the binary by name on `PATH` (`System.getenv('PATH')` split + executable check per OS, or via `ProcessBuilder("which"/"where", name)`); returns the resolved absolute path or null.
- `detectTerminalCommand()` — tries an ordered list of known terminal emulators per OS and returns the first one found.

### `AiAssistantLauncher` (new, `service/AiAssistantLauncher.groovy`)
- `launch(AiClient client, String binaryPath, String terminalCommand, Path workspace, Map<String,String> extraEnv)` — builds the platform-specific command line (Linux: `<terminalCommand> -- <binaryPath>` or emulator-specific flag; macOS: `open -na Terminal --args ...` won't set cwd directly, so use a small generated shell invocation or `Terminal.app`'s "do script" via `osascript` with `cd`; Windows: `wt.exe -d <workspace> <binaryPath>` or `cmd /c start`) and spawns it via `ProcessBuilder` with `directory(workspace.toFile())` and merged environment.
- Surfaces failures (binary not found, no terminal found, spawn `IOException`) to the caller rather than swallowing them.

### `McpSettingsSection.groovy` additions
- `JComboBox<AiClient>` + "Launch AI Assistant" button, in a new row.
- Per-client binary-path `JTextField` + small "Detect" button (4 rows, or a nested panel), and one shared terminal-command `JTextField` + "Detect" button.
- On section load: for any field that's blank, auto-run detection once and populate it (and persist via `UserPreferencesService`), matching the existing `ensureMcpToken()`-style "ensure on first use" pattern.
- Fields are editable — whatever is currently in the field (persisted) is what gets used at launch; no separate silent fallback branch at launch time.

### `UserPreferencesService` additions
New keys, same pattern as `MCP_TOKEN_KEY`:
- `ai.launcher.binary.claude`, `ai.launcher.binary.codex`, `ai.launcher.binary.kimi`, `ai.launcher.binary.vibe`
- `ai.launcher.terminalCommand`

Simple `getAiBinaryPath(AiClient)/setAiBinaryPath(AiClient, String)` and `getTerminalCommand()/setTerminalCommand(String)` accessors.

---

## Flow (Launch button)

1. Read the selected `AiClient` and its currently-persisted binary path; if blank, show an error (fields should already be populated by detection-on-load, so this is a defensive fallback, not the primary path).
2. `AiWorkspaceService.ensureWorkspace()`, then `refreshClientFiles(client, LoopbackMcpServer.ENDPOINT, userPreferencesService.ensureMcpToken())`.
3. `AiAssistantLauncher.launch(...)` with the persisted binary path + terminal command, workspace as cwd, and (Codex only) `ACCOUNTING_MCP_TOKEN` in the child environment.
4. On failure at any step, show an error dialog (existing pattern in `McpSettingsSection`/`VoucherPanel` error handling) naming what failed.

---

## Error handling

- Binary path blank/invalid at launch time → error dialog naming the client and suggesting the user fill in or re-detect the field.
- No terminal emulator resolvable (can happen on minimal Linux setups) → error dialog listing what was tried, suggesting manual entry in the terminal-command field.
- Config file write failure (permissions/disk) → error dialog, same pattern as existing settings-save failures.

---

## Testing

- Unit tests per `AiClient` config writer: verify exact file contents and paths for each of the 4 formats (no real CLI needed) — e.g. assert the JSON/TOML written matches expected structure given a fake endpoint/token.
- Unit tests for `detectBinaryPath`/`detectTerminalCommand` using a fake `PATH`/fake executables in a `@TempDir`, not the real system PATH.
- Terminal-spawn itself is not realistically unit-testable/CI-friendly (it launches a real GUI terminal) — note as a manual verification step per platform in the PR description, per project convention for Swing/desktop-integration changes.
