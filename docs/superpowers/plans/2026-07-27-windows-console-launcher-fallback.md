# Windows Console Launcher Fallback Implementation Plan

**Goal:** Make the AI Assistant launcher work on Windows installations without a usable Windows Terminal (`wt.exe`), including older Windows versions, while fixing detection and validation of Microsoft Store App Execution Aliases reported in [issue #105](https://github.com/Alipsa/accounting/issues/105).

**Architecture:** Treat `cmd.exe` as the supported classic-console adapter. Do not launch `conhost.exe` directly: it is the console host Windows creates for console applications, not a stable application-launch interface. The `COMMAND_PROMPT` adapter invokes `cmd.exe /v:off /c <wrapper.cmd>` directly through `ProcessBuilder`; `/v:off` forces delayed expansion off so literal `!` values in the wrapper cannot be unexpectedly expanded. Windows supplies the classic console host when necessary. Windows Terminal remains an optional adapter. `AiWorkspaceService.detectTerminalAdapter()` owns selection: it first resolves `wt.exe` through the existing `PathBinaryResolver` (`PATH`/`PATHEXT` plus the existing `ExecutableProbe` interface, implemented in production by `FileSystemExecutableProbe`), then reads `LOCALAPPDATA` through its injected `EnvironmentLookup` and checks `<LOCALAPPDATA>\\Microsoft\\WindowsApps\\wt.exe` through that same probe, and finally resolves and validates `cmd.exe` through the same resolver/probe before returning Command Prompt. The local `WindowsApps` alias location is the supported default target; package-internal installation paths are deliberately not discovered.

**Security constraints:** Preserve the existing fail-closed Windows path validation. Direct `cmd.exe` uses the same wrapper and `cmd.exe` parser as the existing Windows Terminal path, so workspace/script paths must still reject `&`, `|`, `<`, `>`, and `^` before any secret-bearing wrapper is written. Do not reintroduce `cmd /c start`: `start` adds another command-language parser and argument/title edge cases.

**Non-goal:** This change does not add a PowerShell fallback adapter. A PowerShell adapter needs a separate `.ps1` wrapper and independent quoting/security review.

## Scope

### Main code

- Modify `app/src/main/groovy/se/alipsa/accounting/domain/TerminalAdapterKind.groovy`
  - Add `COMMAND_PROMPT('cmd.exe')`.
  - Return `[WINDOWS_TERMINAL, COMMAND_PROMPT]` for Windows, preserving the optional Terminal choice while enabling fallback.
- Modify `app/src/main/groovy/se/alipsa/accounting/service/TerminalCommandBuilder.groovy`
  - Add a `COMMAND_PROMPT` case that rejects unsafe paths and returns `[cmdPath, '/v:off', '/c', scriptPath]`.
  - Extract the shared Windows path validation so the two Windows adapters cannot diverge.
- Modify `app/src/main/groovy/se/alipsa/accounting/service/AiAssistantLauncher.groovy`
  - Generate a `.cmd` wrapper for both `WINDOWS_TERMINAL` and `COMMAND_PROMPT`.
  - Run preflight path validation for both Windows adapter kinds.
  - Build and validate the terminal command before writing the token-bearing wrapper; a failed preflight must leave no `.cmd` file behind.
- Modify `app/src/main/groovy/se/alipsa/accounting/service/FileSystemExecutableProbe.groovy`
  - Add Windows-aware validation for an existing `.exe` App Execution Alias reparse point, without weakening regular executable validation on Unix or accepting arbitrary non-files.
  - Keep the special case narrowly scoped to `.exe` candidates and Windows; a directory, missing path, or non-`.exe` reparse point must remain invalid.
- Modify `app/src/main/groovy/se/alipsa/accounting/service/AiWorkspaceService.groovy`
  - Keep terminal selection here (not in the Swing section): resolve `wt.exe` from `PATH` first, then obtain `LOCALAPPDATA` through the injected `EnvironmentLookup` and probe `<LOCALAPPDATA>\\Microsoft\\WindowsApps\\wt.exe` for the Store alias, then resolve `cmd.exe` through `PathBinaryResolver`.
  - Return `null` only after both adapters fail their `ExecutableProbe` validation; this prevents a nonexistent `cmd.exe` from being selected and producing a later confusing launch error.
- Modify `app/src/main/groovy/se/alipsa/accounting/ui/AiAssistantLauncherSection.groovy`
  - When an explicit Detect action finds nothing, show a localized, actionable message instead of silently doing nothing.
  - Keep startup auto-detection quiet; it should not show a dialog merely because a binary is absent.
- Modify `app/src/main/resources/i18n/messages.properties` and `messages_sv.properties`
  - Add one reusable `aiLauncher.detection.notFound` key with a `{0}` display-name parameter, rather than per-binary/adapter keys.
- Update `docs/ai-assistenten.md` and the launcher design/spec documentation to describe `cmd.exe` fallback and explicitly state that `conhost.exe` is not configured directly.
- Modify `release.md` under the current release's `### Buggfixar` section with the Windows compatibility fix.

### Tests

- Modify `app/src/test/groovy/unit/se/alipsa/accounting/domain/TerminalAdapterKindTest.groovy`
  - Assert Windows exposes Windows Terminal and Command Prompt, and non-Windows lists are unchanged.
- Modify `app/src/test/groovy/unit/se/alipsa/accounting/service/TerminalCommandBuilderTest.groovy`
  - Assert the exact direct `cmd.exe /v:off /c <script>` argument list.
  - Assert every unsafe Windows command character is rejected for Command Prompt as well as Windows Terminal.
- Create or extend `FileSystemExecutableProbeTest.groovy`
  - Test normal files, directories, missing paths, and the injectable/isolated Windows alias decision logic.
  - Test that a Windows reparse point is accepted only when it has an `.exe` suffix; a non-`.exe` reparse point, including one under `WindowsApps`, remains rejected.
  - Do not rely on a real Store alias in Linux CI; isolate the platform/filesystem seam so its behavior is unit-testable.
- Modify `AiWorkspaceServiceTest.groovy`
  - Assert the precise priority: valid PATH `wt.exe` > valid Store alias `wt.exe` > valid `cmd.exe` > `null`.
  - Assert Command Prompt is selected when neither PATH nor the `%LOCALAPPDATA%` Store alias resolves `wt.exe` but `cmd.exe` does.
  - Assert no adapter is returned when `cmd.exe` also fails the same executable probe.
- Modify `AiAssistantLauncherTest.groovy`
  - Assert Command Prompt writes a `.cmd` wrapper, runs the direct command, and fails preflight before writing when the workspace path is unsafe.
  - Assert a preflight failure leaves no generated wrapper on disk.
- Modify `AiAssistantLauncherSectionTest.groovy`
  - Assert a user-initiated failed detection displays the localized feedback, while automatic initial detection remains non-modal.

## Implementation Steps

- [ ] Add failing enum and command-builder tests for `COMMAND_PROMPT`, including unsafe-path cases and the exact `/v:off` command list.
- [ ] Implement `COMMAND_PROMPT` in `TerminalAdapterKind` and `TerminalCommandBuilder`; extract shared Windows validation.
- [ ] Update `AiAssistantLauncher` so both Windows adapter kinds select `.cmd` wrapper generation and preflight validation.
- [ ] Add a small Windows/filesystem seam to `FileSystemExecutableProbe` if needed, then implement the narrowly scoped App Execution Alias acceptance rule and its tests.
- [ ] Update `AiWorkspaceService` detection tests and implementation: use `PathBinaryResolver` for both executables, obtain `LOCALAPPDATA` through `EnvironmentLookup`, probe `<LOCALAPPDATA>\\Microsoft\\WindowsApps\\wt.exe` after PATH resolution, verify the order PATH `wt.exe` > Store alias `wt.exe` > `cmd.exe` > `null`, and fall back only to a probe-validated `cmd.exe`.
- [ ] Add explicit Detect failure feedback in `AiAssistantLauncherSection`, translations, and UI tests. Keep startup auto-detection silent.
- [ ] Update user documentation, the launcher design record, and release notes.
- [ ] Run `./gradlew spotlessApply`, inspect all source and Markdown changes, then run `./gradlew codenarcMain`.
- [ ] Run `./gradlew build` and perform a manual Windows smoke test on:
  - Windows 10 without Windows Terminal;
  - Windows 11 with Store-installed Windows Terminal and its App Execution Alias enabled;
  - Windows Terminal installed with its App Execution Alias disabled (must fall back to Command Prompt);
  - a path containing spaces; and
  - a wrapper path containing `!`, proving `/v:off` preserves it literally; and
  - refusal of a workspace path containing each unsafe `cmd.exe` metacharacter.

## Acceptance Criteria

- On supported Windows versions, a user can launch the AI assistant without Windows Terminal installed.
- The app never invokes `conhost.exe` directly; Windows creates it as the classic console host when needed.
- A Store-installed `wt.exe` alias can be detected and manually accepted only under the narrow Windows `.exe` rule.
- Windows Terminal remains preferred when its PATH or Store-alias path passes the executable probe; Command Prompt is selected when Windows Terminal is absent, its Store alias is disabled, or its resolved alias is unusable.
- Command Prompt is returned only after `cmd.exe` itself passes the same executable probe; otherwise detection reports no usable terminal adapter.
- Failed explicit detection gives useful feedback, while automatic detection does not create noisy startup dialogs.
- All Windows launch paths retain current secret-handling and command-injection protections.
