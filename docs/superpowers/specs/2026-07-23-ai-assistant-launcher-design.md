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

### Location: fixed per-user default, not the (possibly shared/custom) application home

Earlier drafts placed the workspace at `AppPaths.applicationHome().resolve('ai-workspace')`. `applicationHome()` honors both the `HOME_OVERRIDE_PROPERTY` and the user-facing configurable-data-location feature, so it can legitimately point at a shared/networked location — that's the right behavior for *accounting data*, which several machines/users may need to access. There is no equivalent reason for the AI workspace to ever live somewhere shared: nothing about launching a local AI CLI session needs multi-machine access, and putting bearer-token-bearing files on a location another party can write to is exactly the "hostile shared mount" scenario that motivates the permission and symlink defenses below.

So instead: add a new `AppPaths.aiWorkspaceDirectory()` that resolves under the **fixed OS-default per-user location** (the same computation `osDefaultApplicationHome()` already does — `~/.local/share/alipsa-accounting` on Linux, `~/Library/Application Support/AlipsaAccounting` on macOS, `%APPDATA%\Alipsa\Accounting` on Windows), independent of any `HOME_OVERRIDE_PROPERTY` or user-configured custom/shared data location. This requires a small, additive change to `AppPaths.groovy`: expose the OS-default resolution (today private) so `aiWorkspaceDirectory()` can use it while `dataDirectory()` etc. keep using the overridable `applicationHome()` as before.

This removes the shared/hostile-mount threat model for this directory specifically at the root cause. The fail-closed permission verification and `verifyNoSymlinksInPath` checks below are retained anyway as defense-in-depth (a compromised local process on the same machine is still a real, if narrower, threat), but the more invasive descriptor-relative/TOCTOU-proof techniques (e.g. `SecureDirectoryStream`, which also isn't available on Windows through the default NIO provider) are not needed once the directory itself is no longer reachable via a user-configurable/shared mount.

This directory is **fully app-managed** — nothing else should write user content there. Each client's own config + instructions file is refreshed whenever *that* client is launched, to keep its endpoint/token current (see Secret lifecycle below for exactly what gets touched, and when a broader all-clients purge happens instead). It contains, per client:

| Client | Config file (relative to workspace) | Format | Instructions file |
|---|---|---|---|
| Claude Code | `.mcp.json` | JSON | `.claude/skills/accounting/accounting-mcp.md` (file copy) |
| Codex | `.codex/config.toml` | TOML | `AGENTS.md` (file copy) |
| Kimi | `.kimi-code/mcp.json` | JSON | `AGENTS.md` (file copy) — **experimental**, see Client verification status below |
| Vibe | `.vibe/config.toml` | TOML | `AGENTS.md` (file copy) — **experimental**, see Client verification status below |

All instructions files are plain copies of the classpath skill resource — **no symlinks anywhere** (see Skill file: copy, not symlink below).

Claude Code's config carries a literal `Authorization: Bearer <token>` header; same for Kimi and Vibe. Codex's config references the env-var name `ACCOUNTING_MCP_TOKEN`, which is only ever set inside the generated launch wrapper script (see Launch wrapper script below), never in the user's shell profile or any long-lived environment. **The token therefore appears as text in more than one place** — the Claude/Kimi/Vibe config files *and* every client's wrapper script — all of which are treated as one "secret set" with the same permission and cleanup guarantees (see Secret lifecycle below); no single file is claimed to be the only place it lives.

TOML is written by hand with simple string formatting (the schema needed is a small fixed `[mcp_servers.accounting]` table) — no new TOML library dependency.

### Workspace and file permissions (fixes: plaintext bearer tokens in potentially shared storage; must fail closed, not best-effort; must not treat Windows as unsupported; must distinguish modes that need execute permission)

`AppPaths.ensureDirectoryStructure()` deliberately does **not** tighten permissions when `applicationHome()` is a user-selected/shared location (see the comment at `AppPaths.groovy:112-116`), and even where it does try, `tightenPermissions()` only logs a warning and continues if the attempt fails (`AppPaths.groovy:139-166`). That best-effort behavior is correct for shared accounting data, but wrong for a directory holding bearer-token secrets. (The location change above already removes the *shared-mount* version of this concern for the AI workspace specifically; the fail-closed behavior below is kept regardless, as defense-in-depth against a compromised local process.)

`AiWorkspaceService` therefore treats permissioning as **fail-closed**, not best-effort — but "fail closed" must mean *per-platform mechanism fails*, not *non-POSIX filesystem detected*, since Windows is a supported platform and NTFS has no POSIX permission bits at all (`Files.getPosixFilePermissions` throws `UnsupportedOperationException` there unconditionally, which would make the naive fail-closed check refuse Windows outright). The service picks its restriction mechanism from `Path.getFileSystem().supportedFileAttributeViews()`:

- **`"posix"` supported** (Linux, macOS): create with owner-only permissions **set at creation time** via `PosixFilePermissions.asFileAttribute(...)` passed directly to `Files.createDirectory`/`Files.createFile` — never create-with-default-permissions-then-chmod-after, which leaves a window where the file is briefly more permissive than intended. The exact mode differs by what the path is, since wrapper scripts must be *executable* to be invoked directly by a terminal adapter (`xterm -e script`/`gnome-terminal -- script` perform an `execve` on the script itself, which requires the execute bit regardless of its `#!` shebang line — a generic 0600 would make every launch fail with "permission denied"):
  - `ai-workspace/` and its subdirectories (`.codex/`, `.claude/skills/accounting/`, `.kimi-code/`, `.vibe/`): **0700** (execute needed for traversal).
  - Config files and instructions files (`.mcp.json`, `config.toml`, `mcp.json`, `AGENTS.md`, skill copies): **0600** (read/write only, never executed).
  - Unix wrapper scripts (`.launch-<client>-<uuid>.sh`): **0700** (must be executable).
  - Immediately after creation, **and again after every atomic move into place** (see Secret lifecycle below — a temp file's mode should survive a POSIX rename, but this is verified rather than assumed), **read back** `Files.getPosixFilePermissions` on the final destination path and verify it matches the expected mode for that path's kind.
- **`"acl"` supported, `"posix"` not** (Windows/NTFS): after creating the file/directory (Java's ACL API only applies to an existing path — there is no creation-time ACL attribute equivalent to `PosixFilePermissions.asFileAttribute`), resolve the file's own owner via `Files.getOwner(path)` (not a separate `user.name`-based lookup through `path.getFileSystem().getUserPrincipalLookupService()`, which can mismatch in domain-account setups) and replace the ACL via `Files.getFileAttributeView(path, AclFileAttributeView)` with a single ACE granting only that owner full control, removing inherited/broader ACEs (e.g. built-in `Users`/`Everyone`). Read the ACL back afterward (and again after every atomic move) and verify it contains only that one owner ACE. There is an inherent brief window between file creation and ACL replacement where the file may carry the parent directory's inherited permissions — this is a known limitation of the JDK's ACL API (no atomic "create with this ACL" primitive), accepted here given the location change above already keeps this directory off any shared/custom mount, so the parent (`%APPDATA%\Alipsa\Accounting\ai-workspace`) is already a normal, non-shared per-user profile directory on every shipped Windows configuration.
- **Neither `"posix"` nor `"acl"` supported**: refuse — not expected on any of the three shipped platforms.

In all cases, if the platform-appropriate verification step fails, refuse to write the credential-bearing file and surface an error rather than silently proceeding with weaker protection.

Before writing to any path in the workspace, verify no symlink exists anywhere along the path — see Symlink checks below (a single final-target check is not sufficient).

### Symlink checks cover every path component (fixes: checking only the final target misses a symlinked parent)

Checking `Files.isSymbolicLink()` on only the final file (e.g. `ai-workspace/.codex/config.toml`) does not protect against an *ancestor* directory — `ai-workspace` itself, or `.codex`, or `.claude/skills` — having been replaced with a symlink; a write would then land wherever that parent link points, following it transparently. `AiWorkspaceService` therefore has a `verifyNoSymlinksInPath(Path candidate)` helper that walks every path segment from the `ai-workspace` root down to (and including) `candidate`, checking each with `Files.isSymbolicLink` (which does not follow links), and refuses the operation if any segment — intermediate directory or final file — is a symlink. This check runs before every create, write, move, and delete inside the workspace, not just before the final leaf write.

### Skill file: copy, not symlink (fixes: symlink is non-portable and update-fragile)

Symlinking (the original plan for Claude Code specifically) requires elevated privileges/developer mode on Windows and breaks if the app's install path changes. Since each client's own instructions file is refreshed on every launch of that client anyway, and the skill content is now a small classpath resource (see above), every client gets a plain file **copy** of that resource content — uniform across all four clients, no platform-specific symlink logic needed at all.

---

## Terminal adapters (fixes: free-text terminal command risks broken launches and shell injection)

A free-text "terminal command" string cannot be safely combined with per-platform invocation styles, and building a shell command string to embed a secret is itself a risk (shell injection, secrets visible in `ps`/shell history). Instead:

- A fixed, small set of known adapters, one per supported terminal, each implementing `List<String> commandFor(Path executable, Path scriptToRun)`:
  - Linux: `GnomeTerminalAdapter` (`["gnome-terminal", "--", script]`), `KonsoleAdapter` (`["konsole", "-e", script]`), `XtermAdapter` (`["xterm", "-e", script]`). These pass the script path as a discrete argument via `ProcessBuilder(List<String>)` with no further string-language parsing on the receiving end, so no extra escaping layer is needed here.
  - Windows: `WindowsTerminalAdapter` (`["wt.exe", "-d", workspace, script]`) is the **only** Windows adapter — like the Linux adapters, `wt.exe` takes discrete arguments with no secondary parsing. An earlier draft also specified a `CmdAdapter` (`cmd /c start ...`) fallback; it's dropped entirely: `cmd.exe`'s `start` re-parses its arguments as command-language text even when Java passes them as a `List<String>` (a leading quoted argument is read as a window title unless an explicit empty one is supplied; `%`, `!`, `&`, `|`, `<`, `>` are all separately meaningful to `cmd.exe`), which is a wide, error-prone escaping surface for very little benefit — Windows Terminal ships inbox on Windows 11 and is a Microsoft Store install on Windows 10. If `wt.exe` isn't found, that's treated the same as "no terminal adapter resolved" (see Error handling), with the message pointing the user at installing Windows Terminal rather than silently falling back to a fragile secondary path.
  - macOS: `TerminalAppAdapter` — invokes `osascript` to tell `Terminal.app` to run the generated wrapper **script path**. This has **two escaping layers**, not zero: (1) `Terminal.app`'s `do script "<text>"` feeds `<text>` to the user's shell, so the script path must be **shell-quoted** (wrap in `'...'`, escaping embedded `'` as `'\''`) — required because `applicationHome()`, and therefore the workspace path, can be a user-configured location that may contain spaces or shell-meaningful characters; (2) that shell-quoted text is itself embedded in an AppleScript string literal passed to `osascript`, so it must also be **AppleScript-escaped** (escape `\` and `"`). Both `shellQuoteSingle(String)` and `appleScriptEscape(String)` are small, separately unit-tested helpers, composed as `appleScriptEscape(shellQuoteSingle(scriptPath))`; tests cover adversarial paths (spaces, single quotes, double quotes, backslashes).
- Detection (`AiWorkspaceService.detectTerminalAdapter()`) tries these in order per OS and returns the first whose executable resolves.
- The user-facing override field holds a **path to the adapter's executable**, not an arbitrary command string; the fixed argument-list convention for that adapter type is still applied. There's a small closed set of adapter *types* (matching the list above); the override only changes which binary is invoked, not how arguments are assembled. If detection found a known adapter, an override to a different path keeps that same adapter type's argument convention (e.g. overriding the detected `xterm` path still uses `-e`). If detection found **no** known adapter at all and the user supplies a path manually, the generic `xterm`-style `-e` convention is assumed as a documented best-effort default for v1.
- All process spawning uses `ProcessBuilder(List<String>)` directly — no shell (`sh -c`, `cmd /c` with a concatenated string, etc.) is ever constructed from user or secret input.

### Launch wrapper script (fixes: Codex token propagation not guaranteed, especially via macOS `open`/`osascript`)

`ProcessBuilder.environment()` only reliably applies to the process we directly spawn. Terminal emulators that fork/exec a shell normally propagate environment down the process tree, but macOS app launches via `open`/`osascript`/LaunchServices are a documented exception — they do **not** reliably inherit the launching process's environment. Relying on env-var inheritance through an arbitrary terminal emulator is therefore fragile across platforms, not just macOS.

Fix: `AiAssistantLauncher` writes a small **per-launch wrapper script** into the workspace, with a unique name per invocation of Launch — `ai-workspace/.launch-<client>-<uuid>.sh` on Unix, `.launch-<client>-<uuid>.cmd` on Windows (the uuid suffix is what resolves the cross-launch race described in Wrapper naming and cleanup timing below). Every terminal adapter is only ever told to run this wrapper script's path — the token never appears in a terminal command line, an AppleScript string, or a process argument list.

The wrapper's own content is itself shell/batch source and therefore just as injectable as any other generated script if paths are substituted in naively with double quotes — `"$( ... )"` and `` `...` `` still execute inside double-quoted strings in `sh`. The Unix wrapper therefore uses the same `shellQuoteSingle()` helper defined for the macOS terminal adapter (see Terminal adapters above) for **every** substituted value, not double quotes:

```sh
#!/bin/sh
export ACCOUNTING_MCP_TOKEN='<token, single-quote-escaped>'
cd '<workspace, single-quote-escaped>'
exec '<binaryPath, single-quote-escaped>'
```

The Windows `.cmd` wrapper — needed regardless of which adapter runs it, since `wt.exe` still just launches a shell that reads this script — is a different scripting language with its own escaping rules (`%`/`!` variable expansion, `&`, `^`, quote handling). It gets its own dedicated, separately unit-tested `escapeForCmdScript(String)` helper for values substituted into `.cmd` file content. If any value contains a character that `escapeForCmdScript` cannot represent safely, `AiWorkspaceService` refuses to write the wrapper and surfaces an error rather than emitting an unsafe script.

(Non-Codex clients don't need the exported var, but get the same wrapper shape for consistency — `cd` + `exec` only.)

### Secret lifecycle: atomic writes and cleanup (fixes: wrapper/config secret model was inconsistent and incomplete; atomic-write and cleanup policy was missing; purge-on-every-launch raced with in-flight launches)

The "secret set" is: every client's config file (Claude/Kimi/Vibe carry a literal token; Codex's config only carries the env-var name) and every wrapper script (always carries the token, all clients). Rules:

1. **Atomic, permission-safe writes.** Never write a secret-bearing file in place. All such writes go through a small `SecretFileWriter` abstraction (real implementation: write to a sibling temp file in the same directory, e.g. `.mcp.json.tmp-<random>`, created with owner-only permissions from the start — same platform-appropriate mechanism as directory creation, not chmod/ACL-after — then `Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)`, followed by re-verifying the destination's permissions/ACL). This means a crash mid-write never leaves a partially written config or wrapper behind — the target either has the old complete content or the new complete content, never a fragment. If the filesystem doesn't support atomic move (`Files.move` throws `AtomicMoveNotSupportedException` — possible on some network/custom mounts), that is treated as a **launch-blocking error**, surfaced to the user; it never silently falls back to a non-atomic copy-then-replace, which would reintroduce the partial-write risk this rule exists to prevent. `SecretFileWriter` is a separate seam from `ProcessRunner` (filesystem vs. process-spawning responsibilities), which is what makes it independently fakeable in tests (see Testing).
2. **`refreshClientFiles(client, ...)` only touches that one client's own config + instructions file** — it does not purge or touch any other client's files, and (per Wrapper naming and cleanup timing below) it does not delete any existing wrapper scripts either. This is a deliberate change from an earlier draft that purged every client's files on every single launch: that made launching client B delete client A's still-in-use wrapper script out from under a terminal session that hadn't finished starting yet.
3. **`AiWorkspaceService.purgeAllSecrets()`** deletes every config file for every client and every wrapper script (regardless of client or uuid) in one pass. This broader, "invalidate everything" operation is called only from two deliberate, infrequent events, not from routine launches:
   - The token-rotation coordinator, **before** rotating the token (see Rotation ordering under UserPreferencesService additions below).
   - Application shutdown (hooked into the same lifecycle that already stops `LoopbackMcpServer`).

### Wrapper naming and cleanup timing (fixes: purging on every launch races with a previously opened terminal that hasn't yet read its wrapper)

If wrapper scripts shared one fixed name per client and every launch purged-then-rewrote them, launching client B while client A's terminal was still starting (its shell hadn't yet read/`exec`'d the wrapper) could delete client A's wrapper file out from under it, intermittently breaking that launch based on timing. Since we don't have a cross-platform hook for "the shell has finished reading the script," the fix is to make each wrapper's filename unique per launch (`.launch-<client>-<uuid>.*`, see Launch wrapper script above) rather than trying to time its deletion precisely:
- Concurrent launches (same client or different clients) never collide or delete each other's wrapper, because each gets its own filename.
- Wrapper files are only swept away in bulk by `purgeAllSecrets()`, i.e. at token rotation or app shutdown — both are already-accepted "invalidate active AI sessions" moments (rotation explicitly tells the user this; shutdown is self-evident), so deleting an in-flight wrapper at those moments is intended behavior, not a bug.
- This does still leave one narrow, accepted residual case: rotating the token (or shutting down) at the exact moment a *different* launch's wrapper is mid-read is possible in principle, but both trigger events are rare, deliberate, user-initiated actions where "any AI session that was still starting may need to be relaunched" is a reasonable, documented consequence — unlike the previous per-launch purge, which made the race an incidental side effect of routine, frequent use.
- Config files are a narrower case than wrappers here: their filenames (`.mcp.json`, `.codex/config.toml`, etc.) are dictated by each CLI's own discovery convention and can't be made unique per launch, so the same narrow rotation-time race in principle applies to them too. This is accepted as a documented limitation rather than solved, since renaming them isn't an option.

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
- `purgeAllSecrets()` — deletes every client's config file and every wrapper script in one pass; called only at token rotation (before rotating, see Rotation ordering below) and app shutdown, never from a routine launch (see Wrapper naming and cleanup timing above).
- `refreshClientFiles(AiClient client, String endpoint, String token)` — atomically writes (temp file + `ATOMIC_MOVE`) only that one client's config + instructions file, rejecting the write if any path component is a symlink (`verifyNoSymlinksInPath`).
- `detectBinaryPath(AiClient client)` / `detectTerminalAdapter()` — resolution logic depends only on the injected `EnvironmentLookup` and `ExecutableProbe` (see Testing), not directly on `System.getenv`.

### `AiAssistantLauncher` (new, `service/AiAssistantLauncher.groovy`)
- `launch(AiClient client, Path binaryPath, TerminalAdapter adapter, Path workspace, String token)` — generates a fresh uuid-suffixed wrapper filename, writes it atomically with owner-only permissions (same discipline and symlink checks as `AiWorkspaceService`'s config writes, see Secret lifecycle), builds the adapter's `List<String>` command targeting that script, and spawns it via the injected `ProcessRunner` (see Testing) with `directory(workspace.toFile())`.
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

`UserPreferencesService.regenerateMcpToken()` itself is **not** changed to touch the filesystem — it stays a pure preferences operation, keeping the preferences store decoupled from workspace file lifecycle. Instead, `McpSettingsSection`'s existing "regenerate token" button handler — a UI-level coordinator that already has access to both collaborators — orchestrates the two steps.

### Rotation ordering (fixes: rotating first and purging second can leave stale plaintext behind indefinitely if the purge fails)

The coordinator **purges before rotating**, not after:
1. `aiWorkspaceService.purgeAllSecrets()` — if this throws, the handler stops here: it reports the error and does **not** call `regenerateMcpToken()`. The old token remains the active one and no workspace files were left in an inconsistent state — the user can retry.
2. Only if step 1 succeeds: `userPreferencesService.regenerateMcpToken()`, returning the new token to display in the UI.

This ordering means the only failure mode is "old token still active, purge didn't happen, nothing rotated" (safe, retryable) — never "new token active while old plaintext secrets linger on disk with no path back to cleaning them up," which was the risk with the reverse order.

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
- `escapeForCmdScript`, `shellQuoteSingle`, and `appleScriptEscape` each get unit tests with adversarial inputs (spaces, single/double quotes, backslashes, `$(...)`, backticks, `%`, `!`, `&`, `^`, a combination of all of the above) asserting the exact escaped output.
- **Wrapper-content tests, not just adapter-command tests**: render the actual wrapper script text (both `.sh` and `.cmd` variants) using adversarial workspace/binary paths and tokens (e.g. containing `` $(rm -rf ~) ``, `` `whoami` ``, `; echo pwned`, `%PATH%`, `^&`) and assert the rendered file content is the correctly-escaped literal (single-quoted / cmd-escaped) text — the adapter-command tests only check the `List<String>` passed to `ProcessBuilder`, which is a separate concern from whether the wrapper's own textual content is safe.
- `AiWorkspaceService` permission handling, per platform: a POSIX-path test asserts directories/wrappers get mode 0700 and config/instructions files get mode 0600, and that permission-verification failure causes the service to throw/refuse; an ACL-path test (using a fake `AclFileAttributeView`/`Files.getOwner`-backed abstraction, since real Windows ACLs aren't exercisable in a Linux CI runner) asserts the same fail-closed behavior via the Windows branch, and that the owner principal comes from the file's own owner rather than a separate username lookup; a test asserts `supportedFileAttributeViews()` reporting neither `posix` nor `acl` is refused.
- `verifyNoSymlinksInPath`: a test creates a real symlink at an *intermediate* directory (e.g. workspace-root's child) in a `@TempDir` and asserts a write to a file two levels below it is refused — not just a test of the final-leaf-is-a-symlink case.
- Atomic-write behavior: writes go through a small injectable `SecretFileWriter` abstraction (wraps `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`) — a distinct seam from `ProcessRunner`, since one represents a filesystem responsibility and the other a process-spawning one. A test injects a fake `SecretFileWriter` that fails on the move step and asserts the original file is left untouched (never a partial write); a separate test simulates `AtomicMoveNotSupportedException` from the real writer and asserts the operation fails loudly rather than falling back to a non-atomic replace; a test asserts the destination's permissions/ACL are re-verified after the move, not just after the initial temp-file creation.
- `purgeAllSecrets()`: a test populates config + wrapper files for multiple clients in a temp workspace and asserts all of them are gone after one call.
- Rotation ordering: a test simulates `purgeAllSecrets()` failing and asserts `regenerateMcpToken()` is never called in that case (old token/preferences untouched).
- Wrapper-naming/no-cross-launch-race: a test launches (against fakes) the same and different clients twice in a row and asserts each produces a distinct wrapper filename, and that `refreshClientFiles` for one client never deletes another client's existing wrapper or config file.
- Terminal-spawn itself (the real `ProcessRunner` actually opening a GUI terminal) is not realistically unit-testable/CI-friendly — note as a manual verification step per platform in the PR description, per project convention for Swing/desktop-integration changes. This is also where the Claude/Codex smoke test and any Kimi/Vibe promotion smoke test happen.
