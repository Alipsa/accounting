# AI Assistant Launcher — Design Spec

**Date:** 2026-07-23 (revised after code review)

## Context

The app already bundles an MCP skill file (`skill/accounting-mcp.md`) and runs an embedded, loopback-only HTTP MCP server (`LoopbackMcpServer`, bearer-token auth via `UserPreferencesService`). Today, wiring an AI CLI (Claude Code, Codex, Kimi, Mistral Vibe) up to this server is entirely manual: the user edits the CLI's own config by hand and symlinks the skill file into a client-specific directory, following README instructions.

Two problems with today's manual setup:
1. It's tedious and error-prone (four different config formats, symlink commands per OS).
2. If the MCP server is registered in the CLI's **global** config, every unrelated session of that CLI (in any other project, any time the accounting app isn't running) tries to reach `127.0.0.1:48652` and fails, showing a confusing connection error to the user.

This spec adds a "Launch AI Assistant" feature to the Settings UI: pick a client, click a button, and the app writes a **project-scoped** config into a dedicated workspace directory, then opens the user's terminal with that CLI running in that directory — so the accounting MCP registration is only ever visible in that one context.

---

## Scope

- New workspace concept under the app's existing data home (`AppPaths`).
- Bundling `skill/accounting-mcp.md` as a runtime-readable classpath resource (see Runtime skill source below) — a build-config change, not just a packaging one.
- New `domain/AiClient.groovy` enum (plain value type, no Swing/service dependency — same rationale as other domain enums like `VoucherStatus`).
- New `service/AiWorkspaceService.groovy` (config/instructions writers, permission tightening, detection abstractions) and `service/AiAssistantLauncher.groovy` (terminal adapters, wrapper-script generation, process spawn) — pure file I/O and process management, no Swing dependency, so `service/` rather than `ui/`.
- `McpSettingsSection.groovy` UI additions: client dropdown, Launch button, per-client binary-path field, one terminal-executable-path field, each with a "Detect" button.
- New `UserPreferencesService` keys for the four binary-path overrides and the terminal-executable override.
- I18n keys (sv + default).
- Unit tests for config writers, detection logic, and constructed launch commands, using injected environment/process abstractions (see Testing below).

Out of scope: embedding a terminal inside the Swing UI; shelling out to each vendor's own `mcp add` CLI subcommand (rejected — see Decision below); auto-installing any of the four CLI tools themselves; free-text/arbitrary terminal *commands* (only a fixed set of known adapters, see Terminal adapters below).

---

## Decision: write config files directly, don't shell out to vendor CLIs

Considered shelling out to `claude mcp add`, `codex mcp add`, `kimi mcp add` (Vibe has no such subcommand at all). Rejected because:
- Inconsistent across clients (Vibe has none; the other three have different flag dialects and versions may drift).
- Doesn't cleanly solve Codex's token handling: `codex mcp add` only accepts a bearer token via an env-var *name*, resolved at connect time — awkward if we're not also the ones launching the process.

Instead, the app writes each client's config file directly, in a workspace directory it fully owns, and spawns the CLI itself.

---

## Runtime skill source (fixes: bundled skill has no reliable runtime source)

Today `skill/accounting-mcp.md` is only added to the distribution via the `distributions { main { contents { from(rootProject.file('skill')) ... } } }` block in `app/build.gradle:55-62` — a sibling file in the release zip/tar, **not** part of the application's own classpath/resources. It is not embedded in any jpackage app image. A running instance of the app therefore has no reliable way to locate it on disk (install layout varies by platform and by how the user extracted/moved the release).

Fix: add the skill directory as a resources source in `app/build.gradle`:

```groovy
sourceSets {
  main {
    resources {
      srcDir rootProject.file('skill')
    }
  }
}
```

This compiles `accounting-mcp.md` onto the runtime classpath (e.g. readable via `AiWorkspaceService.class.getResourceAsStream('/accounting-mcp.md')`), independent of packaging/install method. The existing `distributions{}` block is unaffected and can stay (it's still useful for users who want to read the skill file without running the app). `AiWorkspaceService` reads the skill content from this classpath resource — never from a sibling-file lookup on disk.

---

## Workspace layout

New directory: `AppPaths.applicationHome().resolve('ai-workspace')` (mirrors the existing `dataDirectory()`/`attachmentsDirectory()` pattern in `AppPaths.groovy`; add `aiWorkspaceDirectory()` alongside them and register it in `ensureDirectoryStructure()`).

This directory is **fully app-managed** — nothing else should write user content there, so files inside it are always overwritten on each launch to keep the endpoint/token current. It contains, per client:

| Client | Config file (relative to workspace) | Format | Instructions file |
|---|---|---|---|
| Claude Code | `.mcp.json` | JSON | `.claude/skills/accounting/accounting-mcp.md` (file copy) |
| Codex | `.codex/config.toml` | TOML | `AGENTS.md` (file copy) |
| Kimi | `.kimi-code/mcp.json` | JSON | `AGENTS.md` (file copy) — **experimental**, see Client verification status below |
| Vibe | `.vibe/config.toml` | TOML | `AGENTS.md` (file copy) — **experimental**, see Client verification status below |

All instructions files are plain copies of the classpath skill resource — **no symlinks anywhere** (see Skill file: copy, not symlink below).

Claude Code's config carries a literal `Authorization: Bearer <token>` header; same for Kimi and Vibe. Codex's config references the env-var name `ACCOUNTING_MCP_TOKEN`, which is only ever set inside the generated launch wrapper script (see Launch wrapper script below), never in the user's shell profile or any long-lived environment.

TOML is written by hand with simple string formatting (the schema needed is a small fixed `[mcp_servers.accounting]` table) — no new TOML library dependency.

### Workspace and file permissions (fixes: plaintext bearer tokens in potentially shared storage)

`AppPaths.ensureDirectoryStructure()` deliberately does **not** tighten permissions when `applicationHome()` is a user-selected/shared location (see the comment at `AppPaths.groovy:112-116`) — that policy is correct for shared accounting data, but the AI workspace holds bearer-token secrets and is a different sensitivity class. `AiWorkspaceService` therefore **always** applies owner-only permissions to `ai-workspace/` and every file written inside it, regardless of whether the broader application home is the OS default or a shared/custom location — reusing the same `tightenPermissions`-style logic already in `AppPaths`, applied unconditionally here rather than conditionally.

### Skill file: copy, not symlink (fixes: symlink is non-portable and update-fragile)

Symlinking (the original plan for Claude Code specifically) requires elevated privileges/developer mode on Windows and breaks if the app's install path changes. Since the workspace is refreshed on every launch anyway, and the skill content is now a small classpath resource (see above), every client gets a plain file **copy** of that resource content — uniform across all four clients, no platform-specific symlink logic needed at all.

---

## Terminal adapters (fixes: free-text terminal command risks broken launches and shell injection)

A free-text "terminal command" string cannot be safely combined with per-platform invocation styles, and building a shell command string to embed a secret is itself a risk (shell injection, secrets visible in `ps`/shell history). Instead:

- A fixed, small set of known adapters, one per supported terminal, each implementing `List<String> commandFor(Path executable, Path scriptToRun)`:
  - Linux: `GnomeTerminalAdapter` (`["gnome-terminal", "--", script]`), `KonsoleAdapter` (`["konsole", "-e", script]`), `XtermAdapter` (`["xterm", "-e", script]`).
  - Windows: `WindowsTerminalAdapter` (`["wt.exe", "-d", workspace, script]`), `CmdAdapter` (`["cmd", "/c", "start", "cmd", "/k", script]`) as fallback.
  - macOS: `TerminalAppAdapter` — invokes `osascript` to tell `Terminal.app` to run the generated wrapper **script path** (not a shell command string containing the token — see below), with the path itself safely quoted since we control its character set (workspace is app-generated, no user-supplied path segments).
- Detection (`AiWorkspaceService.detectTerminalAdapter()`) tries these in order per OS and returns the first whose executable resolves.
- The user-facing override field holds a **path to the adapter's executable**, not an arbitrary command string; the fixed argument-list convention for that adapter type is still applied. There's a small closed set of adapter *types* (matching the list above); the override only changes which binary is invoked, not how arguments are assembled. If detection found a known adapter, an override to a different path keeps that same adapter type's argument convention (e.g. overriding the detected `xterm` path still uses `-e`). If detection found **no** known adapter at all and the user supplies a path manually, the generic `xterm`-style `-e` convention is assumed as a documented best-effort default for v1.
- All process spawning uses `ProcessBuilder(List<String>)` directly — no shell (`sh -c`, `cmd /c` with a concatenated string, etc.) is ever constructed from user or secret input.

### Launch wrapper script (fixes: Codex token propagation not guaranteed, especially via macOS `open`/`osascript`)

`ProcessBuilder.environment()` only reliably applies to the process we directly spawn. Terminal emulators that fork/exec a shell normally propagate environment down the process tree, but macOS app launches via `open`/`osascript`/LaunchServices are a documented exception — they do **not** reliably inherit the launching process's environment. Relying on env-var inheritance through an arbitrary terminal emulator is therefore fragile across platforms, not just macOS.

Fix: `AiAssistantLauncher` writes a small **per-launch wrapper script** into the workspace (`ai-workspace/.launch-<client>.sh` on Unix, `.launch-<client>.cmd` on Windows), owner-only permissions, regenerated on every launch, whose content is the only place the token ever appears as text:

```sh
#!/bin/sh
export ACCOUNTING_MCP_TOKEN="<token>"
cd "<workspace>"
exec "<binaryPath>"
```

(Non-Codex clients don't need the exported var, but get the same wrapper shape for consistency — `cd` + `exec` only.) Every terminal adapter is only ever told to run this wrapper script's path — the token never appears in a terminal command line, an AppleScript string, or a process argument list; it only exists in a file with owner-only permissions that's regenerated (and thus rotated) on every launch and every token regeneration (see Token regeneration below).

---

## Components

### `AiClient` (new enum, `domain/AiClient.groovy`)
One entry per client (`CLAUDE, CODEX, KIMI, VIBE`), each knowing:
- default binary name (`claude`, `codex`, `kimi`, `vibe`)
- its config file's relative path + a small writer for its format
- its instructions-file relative path (always a content copy, never a symlink)
- display name for the dropdown (I18n key)
- verification status (`SUPPORTED` for Claude/Codex, `EXPERIMENTAL` for Kimi/Vibe — see Client verification status)

### `AiWorkspaceService` (new, `service/AiWorkspaceService.groovy`)
- `ensureWorkspace()` — creates the directory if missing, tightens its permissions unconditionally.
- `refreshClientFiles(AiClient client, String endpoint, String token)` — (re)writes that client's config + instructions file, always overwriting, permissions tightened.
- `detectBinaryPath(AiClient client)` / `detectTerminalAdapter()` — resolution logic depends only on the injected `EnvironmentLookup` and `ExecutableProbe` (see Testing), not directly on `System.getenv`.

### `AiAssistantLauncher` (new, `service/AiAssistantLauncher.groovy`)
- `launch(AiClient client, Path binaryPath, TerminalAdapter adapter, Path workspace, String token)` — writes the launch wrapper script (owner-only permissions), builds the adapter's `List<String>` command targeting that script, and spawns it via the injected `ProcessRunner` (see Testing) with `directory(workspace.toFile())`.
- Surfaces failures (binary not found, no terminal adapter found, spawn `IOException`) to the caller rather than swallowing them.
- Before doing any of this, requires the caller to have already confirmed the MCP server is running (see Flow below) — it does not perform that check itself, to keep it a pure launch mechanism.

### `McpSettingsSection.groovy` additions
- `JComboBox<AiClient>` + "Launch AI Assistant" button, in a new row. The button is disabled (with a tooltip/status explanation) whenever the section's existing MCP status is not `RUNNING`.
- Per-client binary-path `JTextField` + small "Detect" button (4 rows, or a nested panel), and one shared terminal-executable-path `JTextField` + "Detect" button.
- On section load: for any field that's blank, auto-run detection once and populate it (and persist via `UserPreferencesService`), matching the existing `ensureMcpToken()`-style "ensure on first use" pattern.
- Fields are editable — whatever is currently in the field (persisted) is what gets used at launch; no separate silent fallback branch at launch time.

### `UserPreferencesService` additions
New keys, same pattern as `MCP_TOKEN_KEY`:
- `ai.launcher.binary.claude`, `ai.launcher.binary.codex`, `ai.launcher.binary.kimi`, `ai.launcher.binary.vibe`
- `ai.launcher.terminalPath`

Simple `getAiBinaryPath(AiClient)/setAiBinaryPath(AiClient, String)` and `getTerminalPath()/setTerminalPath(String)` accessors.

`regenerateMcpToken()` is extended to also delete any existing per-client config files currently present in `ai-workspace/` (fixes: stale plaintext token left usable until next refresh) — the next Launch click regenerates them fresh; there is no window where a file with a dead token sits around silently failing later.

---

## Flow (Launch button)

1. Check the section's current MCP status (already tracked by `McpSettingsSection` as `STARTING`/`RUNNING`/`UNAVAILABLE`); if not `RUNNING`, refuse with an actionable error ("MCP server unavailable: `<detail>` — cannot launch") rather than proceeding to configure a CLI that can only fail to connect.
2. Read the selected `AiClient` and its currently-persisted binary path and terminal path; if either is blank, show an error (fields should already be populated by detection-on-load, so this is a defensive fallback, not the primary path).
3. `AiWorkspaceService.ensureWorkspace()`, then `refreshClientFiles(client, LoopbackMcpServer.ENDPOINT, userPreferencesService.ensureMcpToken())`.
4. `AiAssistantLauncher.launch(...)` with the persisted binary path + resolved terminal adapter, workspace as the wrapper script's cwd, and the current token embedded only in the generated wrapper script.
5. On failure at any step, show an error dialog (existing pattern in `McpSettingsSection`/`VoucherPanel` error handling) naming what failed.

---

## Error handling

- MCP server not running → actionable error, launch refused (see Flow, step 1).
- Binary path blank/invalid at launch time → error dialog naming the client and suggesting the user fill in or re-detect the field.
- No terminal adapter resolvable (can happen on minimal Linux setups) → error dialog listing what was tried, suggesting manual entry in the terminal-path field.
- Config file write failure (permissions/disk) → error dialog, same pattern as existing settings-save failures.

---

## Client verification status (fixes: client compatibility asserted before it is specified)

- **Claude Code, Codex**: config schema and CLI behavior confirmed via research against current documentation; still needs one real-client manual smoke test per platform before release, but treated as the supported baseline.
- **Kimi, Vibe**: `AGENTS.md` as the instructions-file convention is unconfirmed for Vibe, and only informally confirmed for Kimi (not independently verified against a real Kimi install in this research). Both are marked `EXPERIMENTAL` in the `AiClient` enum and in the UI (e.g. a small "experimental" label next to their dropdown entry). Promoting either to `SUPPORTED` requires one real-client smoke test confirming the instructions file is actually picked up.

---

## Testing

- Unit tests per `AiClient` config writer: verify exact file contents and paths for each of the 4 formats (no real CLI needed) — e.g. assert the JSON/TOML written matches expected structure given a fake endpoint/token.
- `EnvironmentLookup`, `ExecutableProbe`, and `ProcessRunner` are small injectable interfaces (real implementations wrap `System.getenv`, filesystem executable checks, and `ProcessBuilder.start()` respectively). Tests inject fakes: a fake `EnvironmentLookup` with a controlled `PATH`, a fake `ExecutableProbe` backed by a `@TempDir`, and a fake `ProcessRunner` that records the constructed `List<String>` command instead of actually spawning a terminal.
- This makes `detectBinaryPath`/`detectTerminalAdapter` deterministic, and lets tests assert the exact command list built for each terminal adapter (including that no secret ever appears in it) without opening a real GUI terminal.
- Terminal-spawn itself (the real `ProcessRunner` actually opening a GUI terminal) is not realistically unit-testable/CI-friendly — note as a manual verification step per platform in the PR description, per project convention for Swing/desktop-integration changes. This is also where the Claude/Codex smoke test and any Kimi/Vibe promotion smoke test happen.
