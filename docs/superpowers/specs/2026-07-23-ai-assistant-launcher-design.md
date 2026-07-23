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

Claude Code's config carries a literal `Authorization: Bearer <token>` header; same for Kimi and Vibe. Codex's config references the env-var name `ACCOUNTING_MCP_TOKEN`, which is only ever set inside the generated launch wrapper script (see Launch wrapper script below), never in the user's shell profile or any long-lived environment. **The token therefore appears as text in more than one place** — the Claude/Kimi/Vibe config files *and* every client's wrapper script — all of which are treated as one "secret set" with the same permission and cleanup guarantees (see Secret lifecycle below); no single file is claimed to be the only place it lives.

TOML is written by hand with simple string formatting (the schema needed is a small fixed `[mcp_servers.accounting]` table) — no new TOML library dependency.

### Workspace and file permissions (fixes: plaintext bearer tokens in potentially shared storage; must fail closed, not best-effort)

`AppPaths.ensureDirectoryStructure()` deliberately does **not** tighten permissions when `applicationHome()` is a user-selected/shared location (see the comment at `AppPaths.groovy:112-116`), and even where it does try, `tightenPermissions()` only logs a warning and continues if the attempt fails (`AppPaths.groovy:139-166`). That best-effort behavior is correct for shared accounting data, but wrong for a directory holding bearer-token secrets — and `applicationHome()` can itself be a user-configured location (the existing configurable-data-location feature), so the workspace path cannot be assumed to be a "safe," attacker-free filesystem location.

`AiWorkspaceService` therefore treats permissioning as **fail-closed**, not best-effort:
- On `ensureWorkspace()`, create the directory (and any secret-bearing file within it) with owner-only permissions **set at creation time** via `PosixFilePermissions.asFileAttribute(...)` passed directly to `Files.createDirectory`/`Files.createFile` — never create-with-default-permissions-then-chmod-after, which leaves a window where the file is briefly more permissive than intended.
- Immediately after creation (and before every write of secret content), **read back** the actual permissions (`Files.getPosixFilePermissions`) and verify they are owner-only. If verification fails — non-POSIX filesystem, a hostile shared mount, anything — refuse to write the credential-bearing file and surface an error rather than silently proceeding with weaker protection.
- Before writing to any path in the workspace, check `Files.isSymbolicLink(path)`. If the target is (or has become) a symlink, refuse to write through it and surface an error — this guards against another party on a shared/custom-location filesystem pre-planting a symlink to redirect our write elsewhere (a classic shared-directory symlink attack).

### Skill file: copy, not symlink (fixes: symlink is non-portable and update-fragile)

Symlinking (the original plan for Claude Code specifically) requires elevated privileges/developer mode on Windows and breaks if the app's install path changes. Since the workspace is refreshed on every launch anyway, and the skill content is now a small classpath resource (see above), every client gets a plain file **copy** of that resource content — uniform across all four clients, no platform-specific symlink logic needed at all.

---

## Terminal adapters (fixes: free-text terminal command risks broken launches and shell injection)

A free-text "terminal command" string cannot be safely combined with per-platform invocation styles, and building a shell command string to embed a secret is itself a risk (shell injection, secrets visible in `ps`/shell history). Instead:

- A fixed, small set of known adapters, one per supported terminal, each implementing `List<String> commandFor(Path executable, Path scriptToRun)`:
  - Linux: `GnomeTerminalAdapter` (`["gnome-terminal", "--", script]`), `KonsoleAdapter` (`["konsole", "-e", script]`), `XtermAdapter` (`["xterm", "-e", script]`). These pass the script path as a discrete argument via `ProcessBuilder(List<String>)` with no further string-language parsing on the receiving end, so no extra escaping layer is needed here.
  - Windows: `WindowsTerminalAdapter` (`["wt.exe", "-d", workspace, script]`) is the **preferred** adapter — like the Linux adapters, `wt.exe` takes discrete arguments with no secondary parsing. `CmdAdapter` (`cmd /c start ...`) is a **last-resort fallback only**, because `cmd.exe`'s `start` command re-parses its arguments as command-language text even when Java passes them as a `List<String>`: it treats a leading quoted argument as a window title unless an explicit empty title is supplied, and requires internal `"` doubling in any quoted path. When `CmdAdapter` is used, an explicit `escapeForCmd(String path)` helper is required (empty title first: `["cmd", "/c", "start", "\"\"", "\"" + escaped(script) + "\""]`), unit-tested against adversarial paths (spaces, embedded quotes).
  - macOS: `TerminalAppAdapter` — invokes `osascript` to tell `Terminal.app` to run the generated wrapper **script path**. This has **two escaping layers**, not zero: (1) `Terminal.app`'s `do script "<text>"` feeds `<text>` to the user's shell, so the script path must be **shell-quoted** (wrap in `'...'`, escaping embedded `'` as `'\''`) — required because `applicationHome()`, and therefore the workspace path, can be a user-configured location that may contain spaces or shell-meaningful characters; (2) that shell-quoted text is itself embedded in an AppleScript string literal passed to `osascript`, so it must also be **AppleScript-escaped** (escape `\` and `"`). Both `shellQuoteSingle(String)` and `appleScriptEscape(String)` are small, separately unit-tested helpers, composed as `appleScriptEscape(shellQuoteSingle(scriptPath))`; tests cover adversarial paths (spaces, single quotes, double quotes, backslashes).
- Detection (`AiWorkspaceService.detectTerminalAdapter()`) tries these in order per OS and returns the first whose executable resolves.
- The user-facing override field holds a **path to the adapter's executable**, not an arbitrary command string; the fixed argument-list convention for that adapter type is still applied. There's a small closed set of adapter *types* (matching the list above); the override only changes which binary is invoked, not how arguments are assembled. If detection found a known adapter, an override to a different path keeps that same adapter type's argument convention (e.g. overriding the detected `xterm` path still uses `-e`). If detection found **no** known adapter at all and the user supplies a path manually, the generic `xterm`-style `-e` convention is assumed as a documented best-effort default for v1.
- All process spawning uses `ProcessBuilder(List<String>)` directly — no shell (`sh -c`, `cmd /c` with a concatenated string, etc.) is ever constructed from user or secret input.

### Launch wrapper script (fixes: Codex token propagation not guaranteed, especially via macOS `open`/`osascript`)

`ProcessBuilder.environment()` only reliably applies to the process we directly spawn. Terminal emulators that fork/exec a shell normally propagate environment down the process tree, but macOS app launches via `open`/`osascript`/LaunchServices are a documented exception — they do **not** reliably inherit the launching process's environment. Relying on env-var inheritance through an arbitrary terminal emulator is therefore fragile across platforms, not just macOS.

Fix: `AiAssistantLauncher` writes a small **per-launch wrapper script** into the workspace (`ai-workspace/.launch-<client>.sh` on Unix, `.launch-<client>.cmd` on Windows), regenerated on every launch:

```sh
#!/bin/sh
export ACCOUNTING_MCP_TOKEN="<token>"
cd "<workspace>"
exec "<binaryPath>"
```

(Non-Codex clients don't need the exported var, but get the same wrapper shape for consistency — `cd` + `exec` only.) Every terminal adapter is only ever told to run this wrapper script's path — the token never appears in a terminal command line, an AppleScript string, or a process argument list.

### Secret lifecycle: atomic writes and unified cleanup (fixes: wrapper/config secret model was inconsistent and incomplete; atomic-write and cleanup policy was missing)

The "secret set" for a given refresh is: the current client's config file (Claude/Kimi/Vibe carry a literal token; Codex's config only carries the env-var name) and that client's wrapper script (always carries the token, all clients). Two rules apply uniformly to every file in this set:

1. **Atomic, permission-safe writes.** Never write a secret-bearing file in place. Write to a sibling temp file in the same directory (e.g. `.mcp.json.tmp-<random>`), created with owner-only permissions from the start (same `PosixFilePermissions.asFileAttribute` approach as directory creation, not chmod-after), then `Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)`. This means a crash mid-write never leaves a partially written config or wrapper behind — the target either has the old complete content or the new complete content, never a fragment.
2. **One unified cleanup operation.** `AiWorkspaceService.purgeAllSecrets()` deletes every config file and every wrapper script for **all four clients** in one pass (not just the one client currently being refreshed) — the earlier plan of only deleting "config files" for the one active client left stale wrapper scripts (and other clients' configs) holding the old token indefinitely. This single operation is called:
   - Immediately after a token regeneration (see UserPreferencesService additions below — invoked by a higher-level coordinator, not by `UserPreferencesService` itself).
   - On application shutdown (hooked into the same lifecycle that already stops `LoopbackMcpServer`).
   - As the first step of every `refreshClientFiles`/launch cycle for consistency, immediately followed by writing the fresh files for the client being launched — so at any point in time the only secret-bearing files that can exist are the ones for the client most recently launched or refreshed.

A wrapper script therefore has a bounded lifetime: it exists from one launch/rotation until the next launch, rotation, or app shutdown — never indefinitely, even though (unlike a config file) it can't be deleted the instant the spawned terminal starts, since the terminal's shell needs to read and `exec` it first and we have no reliable cross-platform hook for "the shell has finished reading the script."

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
- `ensureWorkspace()` — creates the directory if missing, with owner-only permissions set at creation time; verifies (reads back) that they took effect and fails closed (throws, does not warn-and-continue) if not.
- `purgeAllSecrets()` — deletes every client's config file and wrapper script in one pass (see Secret lifecycle above).
- `refreshClientFiles(AiClient client, String endpoint, String token)` — calls `purgeAllSecrets()`, then atomically writes (temp file + `ATOMIC_MOVE`) that client's config + instructions file, rejecting the write if the target path is a symlink.
- `detectBinaryPath(AiClient client)` / `detectTerminalAdapter()` — resolution logic depends only on the injected `EnvironmentLookup` and `ExecutableProbe` (see Testing), not directly on `System.getenv`.

### `AiAssistantLauncher` (new, `service/AiAssistantLauncher.groovy`)
- `launch(AiClient client, Path binaryPath, TerminalAdapter adapter, Path workspace, String token)` — writes the launch wrapper script (atomically, owner-only permissions — same discipline as `AiWorkspaceService`'s config writes, see Secret lifecycle), builds the adapter's `List<String>` command targeting that script, and spawns it via the injected `ProcessRunner` (see Testing) with `directory(workspace.toFile())`.
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

`UserPreferencesService.regenerateMcpToken()` itself is **not** changed to touch the filesystem — it stays a pure preferences operation, keeping the preferences store decoupled from workspace file lifecycle (mixing the two would make error handling/atomicity of the combined operation unclear: what should happen to the returned token if the filesystem cleanup half fails?). Instead, `McpSettingsSection`'s existing "regenerate token" button handler — a UI-level coordinator that already has access to both collaborators — calls `userPreferencesService.regenerateMcpToken()` and then, as a second explicit step, `aiWorkspaceService.purgeAllSecrets()` (see Secret lifecycle above), reporting any failure of the second step to the user distinctly from the first.

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
- Workspace permissions cannot be verified as owner-only, or a write target is found to be a symlink → error dialog naming the failed check; launch/refresh is refused rather than proceeding with weaker protection.

---

## Client verification status (fixes: client compatibility asserted before it is specified)

- **Claude Code, Codex**: config schema and CLI behavior confirmed via research against current documentation; still needs one real-client manual smoke test per platform before release, but treated as the supported baseline.
- **Kimi, Vibe**: `AGENTS.md` as the instructions-file convention is unconfirmed for Vibe, and only informally confirmed for Kimi (not independently verified against a real Kimi install in this research). Both are marked `EXPERIMENTAL` in the `AiClient` enum and in the UI (e.g. a small "experimental" label next to their dropdown entry). Promoting either to `SUPPORTED` requires one real-client smoke test confirming the instructions file is actually picked up.

### Compatibility fixtures (fixes: hand-written config needs explicit compatibility evidence)

A hand-written config that's syntactically valid but silently ignored by the target CLI (e.g. a schema key the CLI stopped reading after a version bump) would only surface as a confusing runtime failure. To catch that class of drift before release, implementation of each `SUPPORTED` client (Claude Code, Codex initially) must include:
- The exact minimum CLI version confirmed (during implementation, against that vendor's current documentation/changelog) to support HTTP transport with a custom `Authorization` header — recorded as a constant/comment next to that client's config writer, not left implicit.
- A checked-in expected-config fixture file per client under test resources (e.g. `app/src/test/resources/ai-launcher/claude-mcp.json`, `.../codex-config.toml`) containing exactly what `AiWorkspaceService` should produce for a known fake endpoint/token, asserted byte-for-byte (or structurally, for JSON) in that client's config-writer unit test.
- Promoting Kimi/Vibe out of `EXPERIMENTAL` requires the same fixture + version-pinning treatment, in addition to the real-client smoke test above.

---

## Testing

- Unit tests per `AiClient` config writer: verify exact file contents and paths for each of the 4 formats (no real CLI needed) — e.g. assert the JSON/TOML written matches expected structure given a fake endpoint/token.
- `EnvironmentLookup`, `ExecutableProbe`, and `ProcessRunner` are small injectable interfaces (real implementations wrap `System.getenv`, filesystem executable checks, and `ProcessBuilder.start()` respectively). Tests inject fakes: a fake `EnvironmentLookup` with a controlled `PATH`, a fake `ExecutableProbe` backed by a `@TempDir`, and a fake `ProcessRunner` that records the constructed `List<String>` command instead of actually spawning a terminal.
- This makes `detectBinaryPath`/`detectTerminalAdapter` deterministic, and lets tests assert the exact command list built for each terminal adapter (including that no secret ever appears in it) without opening a real GUI terminal.
- `escapeForCmd`, `shellQuoteSingle`, and `appleScriptEscape` each get unit tests with adversarial inputs (spaces, single quotes, double quotes, backslashes, a combination of all four) asserting the exact escaped output.
- `AiWorkspaceService` permission handling: a test that simulates permission-verification failure (e.g. a fake permission-check abstraction returning "not owner-only") asserts the service throws/refuses rather than warning-and-continuing; a test asserts writing to a path that `Files.isSymbolicLink` reports as a symlink is refused.
- Atomic-write behavior: a test asserts `refreshClientFiles` leaves either the old complete file or the new complete file in place, never a partial one (simulate by injecting a `ProcessRunner`-style seam that fails the move step and asserting the original file is untouched).
- `purgeAllSecrets()`: a test populates config + wrapper files for multiple clients in a temp workspace and asserts all of them are gone after one call, including files for clients other than the one just launched.
- Terminal-spawn itself (the real `ProcessRunner` actually opening a GUI terminal) is not realistically unit-testable/CI-friendly — note as a manual verification step per platform in the PR description, per project convention for Swing/desktop-integration changes. This is also where the Claude/Codex smoke test and any Kimi/Vibe promotion smoke test happen.
