# AI Assistant Launcher Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Launch AI Assistant" feature to the Settings UI that writes a project-scoped MCP config, accounting skill, and bookkeeping-assistant instructions for a chosen AI CLI (Claude Code, Codex, Kimi, Mistral Vibe) into a dedicated, fixed-location, permission-hardened workspace, then spawns the user's terminal running that CLI there.

**Architecture:** A new `ai-workspace` directory (fixed OS-default per-user location, independent of the app's configurable/shared data home) holds per-client config, accounting skill, and project instructions files, refreshed on each launch. The bundled `assistant-profile.md` establishes the assistant as a bookkeeping helper, directs it to the MCP skill, and requires careful handling of uncertain or jurisdiction-specific advice. Claude receives that profile as root `CLAUDE.md` alongside its Claude skill; Codex, Kimi, and Vibe receive the profile prepended to their root `AGENTS.md`, followed by the MCP skill. `AiWorkspaceService` owns writing/detection/cleanup; `AiAssistantLauncher` renders a per-launch wrapper script and spawns a terminal adapter to run it. All filesystem writes are atomic and permission-verified (fail-closed, POSIX or Windows ACL); all process spawning uses `ProcessBuilder(List<String>)` with no shell string ever built from secret/user input.

**Tech Stack:** Groovy 5 / Java 21, JUnit 6 (`groovier-junit`), `groovy-json` (already a dependency) for JSON config generation, hand-written TOML strings (no new TOML dependency), java.nio.file (POSIX permissions + ACL) for permission hardening.

**Spec:** `docs/superpowers/specs/2026-07-23-ai-assistant-launcher-design.md` (read this first if anything below is ambiguous — it has the full security rationale from nine review rounds).

## Global Constraints

- Java 21 toolchain; run all commands from the repository root.
- `@CompileStatic` is enforced globally — do not add per-class annotations.
- 2-space indentation; follow existing file conventions (see `McpSettingsSection.groovy`, `AppPaths.groovy`, `UserPreferencesService.groovy` for house style).
- No new runtime dependencies: JSON via existing `groovy-json`; TOML hand-written.
- Never build a shell command string from secret or user-supplied input — always `ProcessBuilder(List<String>)`.
- Every secret-bearing file write is atomic (temp file + `ATOMIC_MOVE`) and permission-verified (fail-closed).
- Run `./gradlew spotlessApply` after each task's implementation, then inspect the diff, then `./gradlew codenarcMain` before moving to the next task's failing test.
- Test placement: this feature touches no database, so all new tests go under `app/src/test/groovy/unit/se/alipsa/accounting/...`, mirroring the main package structure.
- Commit after each task with a short, Swedish, imperative-form message (matching repo convention), e.g. `lägger till AiClient-enum`.

---

## File Structure

**New main files:**
- `skill/assistant-profile.md` — shared bookkeeping-assistant role and safety instructions, bundled as a runtime resource.
- `domain/AiClient.groovy` — enum: CLAUDE, CODEX, KIMI, VIBE.
- `domain/TerminalAdapterKind.groovy` — enum: GNOME_TERMINAL, KONSOLE, XTERM, WINDOWS_TERMINAL, TERMINAL_APP.
- `support/ProcessArgumentEscaping.groovy` — `shellQuoteSingle`, `appleScriptEscape`, `escapeForCmdScript`.
- `service/SecretFileKind.groovy` — enum: EXECUTABLE, DATA.
- `service/AclPermissionAdapter.groovy` — interface for Windows ACL apply/verify.
- `service/RealAclPermissionAdapter.groovy` — real ACL implementation.
- `service/AiWorkspacePermissions.groovy` — fail-closed, platform-aware permission + symlink-chain logic.
- `service/FileMover.groovy` — interface around the atomic rename step.
- `service/SecretFileWriter.groovy` — interface for atomic secret-file writes.
- `service/AtomicSecretFileWriter.groovy` — real implementation.
- `service/AiWorkspacePaths.groovy` — pure path resolution (config/skill/project-instructions/wrapper paths).
- `service/AiClientConfigWriter.groovy` — per-client config file content (JSON/TOML).
- `service/TerminalCommandBuilder.groovy` — per-adapter-kind `ProcessBuilder` argument lists.
- `service/LaunchWrapperScript.groovy` — per-launch wrapper script content (`.sh`/`.cmd`).
- `service/EnvironmentLookup.groovy` — interface wrapping `System.getenv`.
- `service/ExecutableProbe.groovy` — interface wrapping filesystem executable checks.
- `service/FileSystemExecutableProbe.groovy` — real implementation.
- `service/PathBinaryResolver.groovy` — scans `PATH` for a named binary using the two seams above.
- `service/FileDeleter.groovy` — interface seam around `Files.deleteIfExists`, for selective purge-failure tests.
- `service/PurgeResult.groovy` — outcome of a `purgeAllSecrets()` sweep.
- `service/AiWorkspaceService.groovy` — orchestrator: ensure/refresh/purge/detect.
- `service/ProcessRunner.groovy` — interface wrapping `ProcessBuilder.start()`.
- `service/AiAssistantLauncher.groovy` — orchestrator: wrapper write + terminal spawn + failure cleanup.
- `ui/BackgroundTaskRunner.groovy` — seam for running filesystem/PATH-scan work off the EDT and hopping back to apply results.
- `ui/SwingBackgroundTaskRunner.groovy` — real implementation (background thread + `SwingUtilities.invokeLater`).
- `ui/AiAssistantLauncherSection.groovy` — new Settings UI section.

**Modified main files:**
- `app/build.gradle` — bundle `skill/` (the accounting MCP skill and assistant profile) as classpath resources.
- `support/AppPaths.groovy` — add `aiWorkspaceDirectory()` + `AI_WORKSPACE_HOME_OVERRIDE_PROPERTY`.
- `service/UserPreferencesService.groovy` — add binary-path / terminal-adapter-kind / terminal-path keys.
- `ui/McpSettingsSection.groovy` — constructor gains `AiWorkspaceService`; rotation handler reordered (purge-then-rotate), purge runs via `BackgroundTaskRunner` off the EDT.
- `ui/McpServerLifecycle.groovy` — constructor gains `AiWorkspaceService` + `AiAssistantLauncherSection`; wires availability + shutdown purge.
- `ui/MainFrame.groovy` — construct and wire the new collaborators.
- `app/src/main/resources/i18n/messages.properties` + `messages_sv.properties` — new keys.
- `release.md` — new-feature bullet.

**New test files** (all under `app/src/test/groovy/unit/se/alipsa/accounting/...`):
- `domain/AiClientTest.groovy`
- `domain/TerminalAdapterKindTest.groovy`
- `support/ProcessArgumentEscapingTest.groovy`
- `support/AppPathsTest.groovy` — extended (existing file)
- `service/AiWorkspacePermissionsTest.groovy`
- `service/AtomicSecretFileWriterTest.groovy`
- `service/AiWorkspacePathsTest.groovy`
- `service/AiClientConfigWriterTest.groovy` (+ fixtures under `app/src/test/resources/ai-launcher/`)
- `service/TerminalCommandBuilderTest.groovy`
- `service/LaunchWrapperScriptTest.groovy`
- `service/PathBinaryResolverTest.groovy`
- `service/AiWorkspaceServiceTest.groovy`
- `service/AiAssistantLauncherTest.groovy`
- `service/UserPreferencesServiceTest.groovy` (new file — none existed before)
- `ui/SwingBackgroundTaskRunnerTest.groovy`
- `ui/AiAssistantLauncherSectionTest.groovy`
- `ui/McpSettingsSectionTest.groovy` (new file — none existed before)

---

## Task 1: `AiClient` domain enum

**Files:**
- Create: `app/src/main/groovy/se/alipsa/accounting/domain/AiClient.groovy`
- Test: `app/src/test/groovy/unit/se/alipsa/accounting/domain/AiClientTest.groovy`

**Interfaces:**
- Produces: `enum AiClient { CLAUDE, CODEX, KIMI, VIBE }` with fields `String binaryName`, `String configRelativePath`, `String instructionsRelativePath`, `boolean experimental`. Later tasks depend on exactly these field names.

- [ ] **Step 1: Write the failing test**

```groovy
package se.alipsa.accounting.domain

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.Test

class AiClientTest {

  @Test
  void claudeUsesJsonConfigAndSkillDirectory() {
    assertEquals('claude', AiClient.CLAUDE.binaryName)
    assertEquals('.mcp.json', AiClient.CLAUDE.configRelativePath)
    assertEquals('.claude/skills/accounting/accounting-mcp.md', AiClient.CLAUDE.instructionsRelativePath)
    assertFalse(AiClient.CLAUDE.experimental)
  }

  @Test
  void codexUsesTomlConfigAndAgentsInstructions() {
    assertEquals('codex', AiClient.CODEX.binaryName)
    assertEquals('.codex/config.toml', AiClient.CODEX.configRelativePath)
    assertEquals('AGENTS.md', AiClient.CODEX.instructionsRelativePath)
    assertFalse(AiClient.CODEX.experimental)
  }

  @Test
  void kimiUsesJsonConfigAndAgentsInstructions() {
    assertEquals('kimi', AiClient.KIMI.binaryName)
    assertEquals('.kimi-code/mcp.json', AiClient.KIMI.configRelativePath)
    assertEquals('AGENTS.md', AiClient.KIMI.instructionsRelativePath)
  }

  @Test
  void vibeUsesTomlConfigAndAgentsInstructions() {
    assertEquals('vibe', AiClient.VIBE.binaryName)
    assertEquals('.vibe/config.toml', AiClient.VIBE.configRelativePath)
    assertEquals('AGENTS.md', AiClient.VIBE.instructionsRelativePath)
  }

  @Test
  void onlyKimiAndVibeAreMarkedExperimental() {
    assertFalse(AiClient.CLAUDE.experimental)
    assertFalse(AiClient.CODEX.experimental)
    assertTrue(AiClient.KIMI.experimental)
    assertTrue(AiClient.VIBE.experimental)
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "se.alipsa.accounting.domain.AiClientTest"`
Expected: FAIL — `AiClient` class not found.

- [ ] **Step 3: Write the implementation**

```groovy
package se.alipsa.accounting.domain

/**
 * A supported AI CLI target for the "Launch AI Assistant" feature, and the
 * per-client file layout inside the AI workspace.
 */
enum AiClient {

  CLAUDE('claude', '.mcp.json', '.claude/skills/accounting/accounting-mcp.md', false),
  CODEX('codex', '.codex/config.toml', 'AGENTS.md', false),
  KIMI('kimi', '.kimi-code/mcp.json', 'AGENTS.md', true),
  VIBE('vibe', '.vibe/config.toml', 'AGENTS.md', true)

  final String binaryName
  final String configRelativePath
  final String instructionsRelativePath
  final boolean experimental

  AiClient(String binaryName, String configRelativePath, String instructionsRelativePath, boolean experimental) {
    this.binaryName = binaryName
    this.configRelativePath = configRelativePath
    this.instructionsRelativePath = instructionsRelativePath
    this.experimental = experimental
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "se.alipsa.accounting.domain.AiClientTest"`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/domain/AiClient.groovy app/src/test/groovy/unit/se/alipsa/accounting/domain/AiClientTest.groovy
git commit -m "lägger till AiClient-enum"
```

---

## Task 2: `TerminalAdapterKind` domain enum

**Files:**
- Create: `app/src/main/groovy/se/alipsa/accounting/domain/TerminalAdapterKind.groovy`
- Test: `app/src/test/groovy/unit/se/alipsa/accounting/domain/TerminalAdapterKindTest.groovy`

**Interfaces:**
- Produces: `enum TerminalAdapterKind { GNOME_TERMINAL, KONSOLE, XTERM, WINDOWS_TERMINAL, TERMINAL_APP }` with field `String defaultBinaryName`, plus `static List<TerminalAdapterKind> forOsName(String)` and `static List<TerminalAdapterKind> forCurrentOs()`.

- [ ] **Step 1: Write the failing test**

```groovy
package se.alipsa.accounting.domain

import static org.junit.jupiter.api.Assertions.assertEquals

import org.junit.jupiter.api.Test

class TerminalAdapterKindTest {

  @Test
  void linuxOffersTheThreeLinuxKinds() {
    assertEquals(
        [TerminalAdapterKind.GNOME_TERMINAL, TerminalAdapterKind.KONSOLE, TerminalAdapterKind.XTERM],
        TerminalAdapterKind.forOsName('Linux'))
  }

  @Test
  void macosOffersOnlyTerminalApp() {
    assertEquals([TerminalAdapterKind.TERMINAL_APP], TerminalAdapterKind.forOsName('Mac OS X'))
  }

  @Test
  void windowsOffersOnlyWindowsTerminal() {
    assertEquals([TerminalAdapterKind.WINDOWS_TERMINAL], TerminalAdapterKind.forOsName('Windows 11'))
  }

  @Test
  void unknownOsNameFallsBackToLinuxKinds() {
    assertEquals(
        [TerminalAdapterKind.GNOME_TERMINAL, TerminalAdapterKind.KONSOLE, TerminalAdapterKind.XTERM],
        TerminalAdapterKind.forOsName(''))
  }

  @Test
  void defaultBinaryNamesMatchKnownExecutables() {
    assertEquals('gnome-terminal', TerminalAdapterKind.GNOME_TERMINAL.defaultBinaryName)
    assertEquals('konsole', TerminalAdapterKind.KONSOLE.defaultBinaryName)
    assertEquals('xterm', TerminalAdapterKind.XTERM.defaultBinaryName)
    assertEquals('wt.exe', TerminalAdapterKind.WINDOWS_TERMINAL.defaultBinaryName)
    assertEquals('osascript', TerminalAdapterKind.TERMINAL_APP.defaultBinaryName)
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "se.alipsa.accounting.domain.TerminalAdapterKindTest"`
Expected: FAIL — `TerminalAdapterKind` class not found.

- [ ] **Step 3: Write the implementation**

```groovy
package se.alipsa.accounting.domain

/**
 * A known terminal emulator invocation convention. The set is closed and
 * OS-scoped — see AI Assistant Launcher design spec, "Terminal adapters".
 */
enum TerminalAdapterKind {

  GNOME_TERMINAL('gnome-terminal'),
  KONSOLE('konsole'),
  XTERM('xterm'),
  WINDOWS_TERMINAL('wt.exe'),
  TERMINAL_APP('osascript')

  final String defaultBinaryName

  TerminalAdapterKind(String defaultBinaryName) {
    this.defaultBinaryName = defaultBinaryName
  }

  static List<TerminalAdapterKind> forOsName(String osName) {
    String normalized = (osName ?: '').toLowerCase(Locale.ROOT)
    if (normalized.contains('win')) {
      return [WINDOWS_TERMINAL]
    }
    if (normalized.contains('mac')) {
      return [TERMINAL_APP]
    }
    [GNOME_TERMINAL, KONSOLE, XTERM]
  }

  static List<TerminalAdapterKind> forCurrentOs() {
    forOsName(System.getProperty('os.name'))
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "se.alipsa.accounting.domain.TerminalAdapterKindTest"`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/domain/TerminalAdapterKind.groovy app/src/test/groovy/unit/se/alipsa/accounting/domain/TerminalAdapterKindTest.groovy
git commit -m "lägger till TerminalAdapterKind-enum"
```

---

## Task 3: `ProcessArgumentEscaping`

**Files:**
- Create: `app/src/main/groovy/se/alipsa/accounting/support/ProcessArgumentEscaping.groovy`
- Test: `app/src/test/groovy/unit/se/alipsa/accounting/support/ProcessArgumentEscapingTest.groovy`

**Interfaces:**
- Produces: `static String shellQuoteSingle(String)`, `static String appleScriptEscape(String)`, `static String escapeForCmdScript(String)` (throws `IllegalArgumentException` on embedded `"`, or on `&`, `|`, `<`, `>`, `^` — cmd.exe's command-line grammar treats those as operators even inside a double-quoted string *within a batch script line*, the same as it does on cmd.exe's own top-level `/c` command line; quoting a value that embeds one does not make it safe), and the public constant `UNSAFE_WINDOWS_COMMAND_CHARACTERS` holding that same five-character list, reused by `TerminalCommandBuilder` (Task 10) rather than duplicated. `LaunchWrapperScript.windowsContent(...)` (Task 11) runs every value it embeds in the generated `.cmd` file — the workspace path, the binary path, and every `envVars` value — through `escapeForCmdScript`, so this is the single choke point that fails closed for all of them, not something each call site has to separately remember to validate.

- [ ] **Step 1: Write the failing test**

```groovy
package se.alipsa.accounting.support

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows

import org.junit.jupiter.api.Test

class ProcessArgumentEscapingTest {

  @Test
  void shellQuoteSingleWrapsPlainValueInSingleQuotes() {
    assertEquals("'hello'", ProcessArgumentEscaping.shellQuoteSingle('hello'))
  }

  @Test
  void shellQuoteSingleEscapesEmbeddedSingleQuotes() {
    assertEquals("'it'\\''s'", ProcessArgumentEscaping.shellQuoteSingle("it's"))
  }

  @Test
  void shellQuoteSingleNeutralizesCommandSubstitution() {
    String malicious = '$(rm -rf ~)'
    assertEquals("'" + malicious + "'", ProcessArgumentEscaping.shellQuoteSingle(malicious))
  }

  @Test
  void shellQuoteSingleNeutralizesBackticks() {
    String malicious = '`whoami`'
    assertEquals("'" + malicious + "'", ProcessArgumentEscaping.shellQuoteSingle(malicious))
  }

  @Test
  void appleScriptEscapeEscapesEmbeddedDoubleQuotes() {
    assertEquals('say \\"hi\\"', ProcessArgumentEscaping.appleScriptEscape('say "hi"'))
  }

  @Test
  void appleScriptEscapeEscapesEmbeddedBackslashes() {
    assertEquals('a\\\\b', ProcessArgumentEscaping.appleScriptEscape('a\\b'))
  }

  @Test
  void appleScriptEscapeHandlesPlainValue() {
    assertEquals('hello', ProcessArgumentEscaping.appleScriptEscape('hello'))
  }

  @Test
  void escapeForCmdScriptDoublesPercentSigns() {
    assertEquals('%%PATH%%', ProcessArgumentEscaping.escapeForCmdScript('%PATH%'))
  }

  @Test
  void escapeForCmdScriptLeavesPlainTextUnchanged() {
    assertEquals('C:\\Users\\per', ProcessArgumentEscaping.escapeForCmdScript('C:\\Users\\per'))
  }

  @Test
  void escapeForCmdScriptRejectsEmbeddedDoubleQuote() {
    assertThrows(IllegalArgumentException) {
      ProcessArgumentEscaping.escapeForCmdScript('bad"value')
    }
  }

  @Test
  void escapeForCmdScriptRejectsEveryUnsafeCmdMetacharacter() {
    // cmd.exe treats &, |, <, >, ^ as operators even inside a quoted string on a batch script
    // line — quoting alone (which is all a caller could otherwise rely on) does not neutralize
    // them, so escapeForCmdScript must fail closed rather than pass them through.
    ['&', '|', '<', '>', '^'].each { String unsafe ->
      assertThrows(IllegalArgumentException) {
        ProcessArgumentEscaping.escapeForCmdScript("C:\\Users\\Per ${unsafe} Nyfelt")
      }
    }
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "se.alipsa.accounting.support.ProcessArgumentEscapingTest"`
Expected: FAIL — `ProcessArgumentEscaping` class not found.

- [ ] **Step 3: Write the implementation**

```groovy
package se.alipsa.accounting.support

/**
 * Escaping helpers for values embedded in generated shell/batch scripts and
 * AppleScript source. See the design spec's "Terminal adapters" and "Launch
 * wrapper script" sections for the exact escaping contract each is part of.
 */
final class ProcessArgumentEscaping {

  /**
   * Characters cmd.exe's command-line grammar treats as operators (command separator, pipe,
   * redirection, escape) even inside a double-quoted string — both on cmd.exe's own top-level
   * `/c` command line (see {@code TerminalCommandBuilder}, Task 10, which reuses this same list)
   * and on an ordinary line inside a `.cmd` batch script file, since cmd.exe uses the identical
   * parser for both. Quoting a value that contains one of these does not make it safe.
   */
  static final List<String> UNSAFE_WINDOWS_COMMAND_CHARACTERS = ['&', '|', '<', '>', '^']

  private ProcessArgumentEscaping() {
  }

  static String shellQuoteSingle(String value) {
    "'" + value.replace("'", "'\\''") + "'"
  }

  static String appleScriptEscape(String value) {
    value.replace('\\', '\\\\').replace('"', '\\"')
  }

  static String escapeForCmdScript(String value) {
    if (value.contains('"')) {
      throw new IllegalArgumentException(
          'Value cannot be safely represented in a Windows batch script because it contains a double quote.')
    }
    UNSAFE_WINDOWS_COMMAND_CHARACTERS.each { String unsafe ->
      if (value.contains(unsafe)) {
        throw new IllegalArgumentException(
            "Value cannot be safely represented in a Windows batch script because it contains " +
                "'${unsafe}', which cmd.exe's command-line grammar can misinterpret as an operator even inside a quoted string.")
      }
    }
    value.replace('%', '%%')
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "se.alipsa.accounting.support.ProcessArgumentEscapingTest"`
Expected: PASS (11 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/support/ProcessArgumentEscaping.groovy app/src/test/groovy/unit/se/alipsa/accounting/support/ProcessArgumentEscapingTest.groovy
git commit -m "lägger till ProcessArgumentEscaping"
```

---

## Task 4: `AppPaths.aiWorkspaceDirectory()`

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/support/AppPaths.groovy`
- Modify: `app/src/test/groovy/unit/se/alipsa/accounting/support/AppPathsTest.groovy`

**Interfaces:**
- Produces: `AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY` (String constant), `static Path AppPaths.aiWorkspaceDirectory()`.

- [ ] **Step 1: Write the failing tests**

Add to `AppPathsTest.groovy` (extend the existing `@BeforeEach`/`@AfterEach` to also capture/restore the new property, and add two new `@Test` methods):

```groovy
  private String previousAiWorkspaceOverride

  @BeforeEach
  void captureSystemProperties() {
    previousOsName = System.getProperty('os.name')
    previousUserHome = System.getProperty('user.home')
    previousHomeOverride = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    previousAiWorkspaceOverride = System.getProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY)
  }

  @AfterEach
  void restoreSystemProperties() {
    restoreProperty('os.name', previousOsName)
    restoreProperty('user.home', previousUserHome)
    restoreProperty(AppPaths.HOME_OVERRIDE_PROPERTY, previousHomeOverride)
    restoreProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY, previousAiWorkspaceOverride)
  }

  @Test
  void aiWorkspaceDirectoryUsesOsDefaultRegardlessOfHomeOverride() {
    System.setProperty('os.name', 'Linux')
    System.setProperty('user.home', '/tmp/alipsa-home')
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, '/some/shared/custom/location')

    assertEquals(
        Paths.get('/tmp/alipsa-home', '.local', 'share', 'alipsa-accounting', 'ai-workspace'),
        AppPaths.aiWorkspaceDirectory())
  }

  @Test
  void aiWorkspaceDirectoryHonorsItsOwnDedicatedOverride() {
    Path overrideHome = tempDir.resolve('ai-workspace-override')
    System.setProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY, overrideHome.toString())

    assertEquals(overrideHome.toAbsolutePath().normalize().resolve('ai-workspace'), AppPaths.aiWorkspaceDirectory())
  }
```

(Replace the existing `previousOsName`/`previousUserHome`/`previousHomeOverride` field declarations and `@BeforeEach`/`@AfterEach` methods with the versions above — they're additive, just add the one new field/line to each.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "se.alipsa.accounting.support.AppPathsTest"`
Expected: FAIL — `AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY` / `aiWorkspaceDirectory()` not found.

- [ ] **Step 3: Write the implementation**

In `AppPaths.groovy`, add the constant next to `HOME_OVERRIDE_PROPERTY` (~line 17-18):

```groovy
  static final String AI_WORKSPACE_HOME_OVERRIDE_PROPERTY = 'alipsa.accounting.aiWorkspace.home'
```

Add the method right after `osDefaultApplicationHome()` (it deliberately calls that same private method directly, rather than `applicationHome()`, so it never picks up `HOME_OVERRIDE_PROPERTY` or a user-configured custom/shared data location — see the design spec's "Workspace layout" section for why):

```groovy
  /**
   * The AI-assistant-launcher workspace: always a fixed, per-user, non-shared
   * location — deliberately independent of {@link #applicationHome()}, which
   * can point at a user-configured shared/custom location. See the AI
   * Assistant Launcher design spec for why this directory must never be
   * reachable via a shared mount.
   */
  static Path aiWorkspaceDirectory() {
    String override = System.getProperty(AI_WORKSPACE_HOME_OVERRIDE_PROPERTY, '').trim()
    Path home = override ? Paths.get(override).toAbsolutePath().normalize() : osDefaultApplicationHome()
    home.resolve('ai-workspace')
  }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "se.alipsa.accounting.support.AppPathsTest"`
Expected: PASS (all AppPathsTest tests, including the 2 new ones)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/support/AppPaths.groovy app/src/test/groovy/unit/se/alipsa/accounting/support/AppPathsTest.groovy
git commit -m "lägger till AppPaths.aiWorkspaceDirectory()"
```

---

## Task 5: Bundle the accounting skill and assistant profile as classpath resources

**Files:**
- Create: `skill/assistant-profile.md`
- Modify: `app/build.gradle`

**Interfaces:**
- Produces: `accounting-mcp.md` and `assistant-profile.md` become readable via `SomeClass.getResourceAsStream('/<name>')` from anywhere in `app`'s runtime classpath.

- [ ] **Step 1: Add the shared assistant profile**

Create `skill/assistant-profile.md`:

```md
# Alipsa Accounting Assistant

You are an experienced bookkeeping assistant and advisor helping the user work with Alipsa Accounting.

Use the Accounting MCP skill and its tools to inspect and assist with the user's accounting data. Follow the skill's instructions for using the MCP.

Be clear about assumptions and ask for missing facts that affect accounting treatment. Do not invent transactions, balances, tax rules, or legal requirements.

For questions involving current, jurisdiction-specific, or uncertain accounting, tax, payroll, or regulatory guidance, consult the authoritative web resources referenced by the skill before advising. State the relevant jurisdiction and source when it matters.

Explain proposed accounting actions in plain language. Ask for confirmation before creating, changing, posting, or deleting accounting records. When appropriate, recommend that the user consult a qualified accountant or adviser.
```

- [ ] **Step 2: Make the build change**

In `app/build.gradle`, add a `sourceSets` block just before the existing `distributions {}` block (~line 55):

```groovy
sourceSets {
  main {
    resources {
      srcDir rootProject.file('skill')
    }
  }
}

distributions {
  main {
    contents {
      from(rootProject.file('skill')) {
        into('skill')
      }
    }
  }
}
```

(Only the new `sourceSets {}` block is added — `distributions {}` is untouched and stays.)

- [ ] **Step 3: Verify both resources are on the classpath**

Run: `./gradlew compileGroovy processResources -q && find app/build/resources/main -maxdepth 1 \( -iname "accounting-mcp.md" -o -iname "assistant-profile.md" \)`
Expected: prints both resource paths — confirming the files are in the compiled resources output. Task 13's `AiWorkspaceServiceTest` is the behavioral verification that they are written into the launch workspace.

- [ ] **Step 4: Commit**

```bash
git add skill/assistant-profile.md app/build.gradle
git commit -m "lägger till assistentprofil för AI-launcher"
```

---

## Task 6: `SecretFileKind`, `AclPermissionAdapter` + real implementation, `AiWorkspacePermissions`

**Files:**
- Create: `app/src/main/groovy/se/alipsa/accounting/service/SecretFileKind.groovy`
- Create: `app/src/main/groovy/se/alipsa/accounting/service/AclPermissionAdapter.groovy`
- Create: `app/src/main/groovy/se/alipsa/accounting/service/RealAclPermissionAdapter.groovy`
- Create: `app/src/main/groovy/se/alipsa/accounting/service/AiWorkspacePermissions.groovy`
- Test: `app/src/test/groovy/unit/se/alipsa/accounting/service/AiWorkspacePermissionsTest.groovy`

**Interfaces:**
- Consumes: nothing new (pure `java.nio.file`).
- Produces: `enum SecretFileKind { EXECUTABLE, DATA }`. `interface AclPermissionAdapter { void applyOwnerOnly(Path); void verifyOwnerOnly(Path) }`. `class AiWorkspacePermissions` with a no-arg constructor (real ACL adapter) and a `(AclPermissionAdapter)` constructor (for tests), and methods: **`void ensureDirectory(Path root, Path dir)`** — `root` is the AI workspace boundary; permission mutation and directory creation are strictly confined to `root` and its descendants, and `root`'s own parent is only ever read-checked (exists? symlink?), never created or chmod'd/ACL'd, since that parent (the application data home) is the existing `AppPaths.ensureDirectoryStructure()`'s responsibility, not this class's — `void createFileWithPermissions(Path path, SecretFileKind kind)`, `void createFileWithPermissions(Path path, SecretFileKind kind, Set<String> supportedViews)`, `void applyAndVerify(Path path, SecretFileKind kind)`, `void applyAndVerify(Path path, SecretFileKind kind, Set<String> supportedViews)`, `void verifyNoSymlinksInPath(Path root, Path candidate)`. Later tasks (`AtomicSecretFileWriter`, `AiWorkspaceService`, `AiAssistantLauncher`) depend on exactly these method names/signatures.

- [ ] **Step 1: Write the failing tests**

```groovy
package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assumptions.assumeTrue

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

class AiWorkspacePermissionsTest {

  @TempDir
  Path tempDir

  private final AiWorkspacePermissions permissions = new AiWorkspacePermissions()

  @Test
  void ensureDirectoryCreatesOwnerOnlyPermissionsRecursively() {
    assumePosixSupported()
    Path root = tempDir.resolve('ai-workspace')
    Path nested = root.resolve('.codex')

    permissions.ensureDirectory(root, nested)

    Set expected = PosixFilePermissions.fromString('rwx------')
    assertEquals(expected, Files.getPosixFilePermissions(nested))
    assertEquals(expected, Files.getPosixFilePermissions(root))
  }

  @Test
  void ensureDirectoryNeverTouchesPermissionsAboveTheWorkspaceRoot() {
    assumePosixSupported()
    Set<PosixFilePermission> beforeParent = Files.getPosixFilePermissions(tempDir)
    Path root = tempDir.resolve('ai-workspace')

    permissions.ensureDirectory(root, root.resolve('.codex'))

    assertEquals(beforeParent, Files.getPosixFilePermissions(tempDir))
    assertEquals(PosixFilePermissions.fromString('rwx------'), Files.getPosixFilePermissions(root))
  }

  @Test
  void ensureDirectoryRefusesWhenTheTargetItselfIsASymlink() {
    assumePosixSupported()
    assumeTrue(!System.getProperty('os.name', '').toLowerCase(Locale.ROOT).contains('win'))
    Path realDir = tempDir.resolve('real-dir')
    Files.createDirectories(realDir)
    Path linked = tempDir.resolve('linked-workspace')
    Files.createSymbolicLink(linked, realDir)

    assertThrows(IllegalStateException) {
      permissions.ensureDirectory(linked, linked)
    }
  }

  @Test
  void createFileWithPermissionsSetsExecutableModeForWrapperScripts() {
    assumePosixSupported()
    Path file = tempDir.resolve('.launch-codex-abc.sh')

    permissions.createFileWithPermissions(file, SecretFileKind.EXECUTABLE)

    assertEquals(PosixFilePermissions.fromString('rwx------'), Files.getPosixFilePermissions(file))
  }

  @Test
  void createFileWithPermissionsSetsDataModeForConfigFiles() {
    assumePosixSupported()
    Path file = tempDir.resolve('.mcp.json')

    permissions.createFileWithPermissions(file, SecretFileKind.DATA)

    assertEquals(PosixFilePermissions.fromString('rw-------'), Files.getPosixFilePermissions(file))
  }

  @Test
  void applyAndVerifyRejectsNeitherPosixNorAclSupport() {
    Path file = tempDir.resolve('unsupported-file')
    Files.createFile(file)

    assertThrows(IllegalStateException) {
      permissions.applyAndVerify(file, SecretFileKind.DATA, ['basic'] as Set)
    }
  }

  @Test
  void aclBranchAppliesAndVerifiesViaInjectedAdapter() {
    List<String> calls = []
    AclPermissionAdapter fakeAdapter = new AclPermissionAdapter() {
      @Override
      void applyOwnerOnly(Path path) { calls << 'apply' }
      @Override
      void verifyOwnerOnly(Path path) { calls << 'verify' }
    }
    AiWorkspacePermissions aclPermissions = new AiWorkspacePermissions(fakeAdapter)
    Path file = tempDir.resolve('windows-style-file')
    Files.createFile(file)

    aclPermissions.applyAndVerify(file, SecretFileKind.DATA, ['acl'] as Set)

    assertEquals(['apply', 'verify'], calls)
  }

  @Test
  void aclBranchFailsClosedWhenAdapterVerificationThrows() {
    AclPermissionAdapter throwingAdapter = new AclPermissionAdapter() {
      @Override
      void applyOwnerOnly(Path path) { }
      @Override
      void verifyOwnerOnly(Path path) { throw new IllegalStateException('unexpected ACL') }
    }
    AiWorkspacePermissions aclPermissions = new AiWorkspacePermissions(throwingAdapter)
    Path file = tempDir.resolve('windows-style-file-2')
    Files.createFile(file)

    assertThrows(IllegalStateException) {
      aclPermissions.applyAndVerify(file, SecretFileKind.DATA, ['acl'] as Set)
    }
  }

  @Test
  void verifyNoSymlinksInPathRejectsSymlinkedIntermediateDirectory() {
    assumeTrue(!System.getProperty('os.name', '').toLowerCase().contains('win'))
    Path realDir = tempDir.resolve('real-dir')
    Files.createDirectories(realDir)
    Path root = tempDir.resolve('workspace-root')
    Files.createDirectories(root)
    Path linkedIntermediate = root.resolve('.codex')
    Files.createSymbolicLink(linkedIntermediate, realDir)
    Path candidate = linkedIntermediate.resolve('config.toml')

    assertThrows(IllegalStateException) {
      permissions.verifyNoSymlinksInPath(root, candidate)
    }
  }

  @Test
  void verifyNoSymlinksInPathAllowsAnOrdinaryNestedFile() {
    Path root = tempDir.resolve('clean-workspace-root')
    Files.createDirectories(root.resolve('.codex'))
    Path candidate = root.resolve('.codex').resolve('config.toml')

    permissions.verifyNoSymlinksInPath(root, candidate)
  }

  private static void assumePosixSupported() {
    assumeTrue(FileSystems.default.supportedFileAttributeViews().contains('posix'))
  }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "se.alipsa.accounting.service.AiWorkspacePermissionsTest"`
Expected: FAIL — none of the new classes exist yet.

- [ ] **Step 3: Write the implementation**

```groovy
// app/src/main/groovy/se/alipsa/accounting/service/SecretFileKind.groovy
package se.alipsa.accounting.service

/** Distinguishes files needing the execute bit (wrapper scripts, directories) from plain data files. */
enum SecretFileKind {
  EXECUTABLE, DATA
}
```

```groovy
// app/src/main/groovy/se/alipsa/accounting/service/AclPermissionAdapter.groovy
package se.alipsa.accounting.service

import java.nio.file.Path

/** Applies and verifies an owner-only ACL. Only meaningful where the "acl" attribute view is supported (Windows/NTFS). */
interface AclPermissionAdapter {
  void applyOwnerOnly(Path path)
  void verifyOwnerOnly(Path path)
}
```

```groovy
// app/src/main/groovy/se/alipsa/accounting/service/RealAclPermissionAdapter.groovy
package se.alipsa.accounting.service

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.UserPrincipal

/** Owner-only ACL implementation, only ever exercised on a filesystem that supports the "acl" attribute view. */
final class RealAclPermissionAdapter implements AclPermissionAdapter {

  @Override
  void applyOwnerOnly(Path path) {
    AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView)
    UserPrincipal owner = Files.getOwner(path)
    AclEntry entry = AclEntry.newBuilder()
        .setType(AclEntryType.ALLOW)
        .setPrincipal(owner)
        .setPermissions(AclEntryPermission.values() as Set)
        .build()
    view.setAcl([entry])
  }

  @Override
  void verifyOwnerOnly(Path path) {
    AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView)
    UserPrincipal owner = Files.getOwner(path)
    List<AclEntry> acl = view.getAcl()
    boolean ownerOnlyFullControl = acl.size() == 1 &&
        acl[0].principal() == owner &&
        acl[0].type() == AclEntryType.ALLOW &&
        acl[0].permissions() == (AclEntryPermission.values() as Set)
    if (!ownerOnlyFullControl) {
      throw new IllegalStateException("Expected an ACL granting only ${owner} full control on ${path}, found ${acl}.")
    }
  }
}
```

```groovy
// app/src/main/groovy/se/alipsa/accounting/service/AiWorkspacePermissions.groovy
package se.alipsa.accounting.service

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Fail-closed, platform-appropriate permission handling for the AI workspace's
 * secret-bearing files, plus symlink-chain verification. See the design spec's
 * "Workspace and file permissions" and "Symlink checks" sections.
 */
final class AiWorkspacePermissions {

  static final Set<PosixFilePermission> EXECUTABLE_PERMISSIONS = EnumSet.of(
      PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
  static final Set<PosixFilePermission> DATA_PERMISSIONS = EnumSet.of(
      PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

  private final AclPermissionAdapter aclAdapter

  AiWorkspacePermissions() {
    this(new RealAclPermissionAdapter())
  }

  AiWorkspacePermissions(AclPermissionAdapter aclAdapter) {
    this.aclAdapter = aclAdapter
  }

  /**
   * Creates {@code dir} (and any missing intermediate levels) with owner-only
   * permissions, but ONLY within the {@code root} subtree. {@code root}'s own
   * parent — the application data home — is only ever read-checked (does it
   * exist? is it a symlink?), never created or chmod'd/ACL'd: that directory
   * is the existing {@code AppPaths.ensureDirectoryStructure()}'s
   * responsibility. Without this boundary, recursing unconditionally up the
   * parent chain to guarantee a fresh symlink check at every level (see
   * below) would also try to chmod the user's home directory and beyond.
   */
  void ensureDirectory(Path root, Path dir) {
    Path normalizedRoot = root.toAbsolutePath().normalize()
    Path normalizedDir = dir.toAbsolutePath().normalize()
    if (normalizedDir != normalizedRoot && !normalizedDir.startsWith(normalizedRoot)) {
      throw new IllegalArgumentException("${dir} is not the workspace root ${root} or inside it.")
    }
    ensureWithinRoot(normalizedRoot, normalizedDir)
  }

  private void ensureWithinRoot(Path root, Path dir) {
    // Check symlink-ness BEFORE anything else, with no-follow semantics: Files.isDirectory()
    // below follows symlinks, so treating "isDirectory() == true" as "safe to chmod/ACL" without
    // this check first would let a symlinked dir redirect the permission change to its target.
    if (Files.isSymbolicLink(dir)) {
      throw new IllegalStateException("Refusing to operate through a symlink at ${dir}.")
    }
    if (Files.isDirectory(dir)) {
      applyAndVerify(dir, SecretFileKind.EXECUTABLE)
      return
    }
    if (Files.exists(dir)) {
      throw new IllegalStateException("${dir} exists but is not a directory.")
    }
    if (dir == root) {
      // At the workspace root: its parent must already exist. We never create or touch
      // permissions on it — only confirm it's real and not a symlink before creating root itself.
      Path parent = dir.parent
      if (parent == null || !Files.isDirectory(parent)) {
        throw new IllegalStateException(
            "${parent} does not exist; the application data home must already exist before the AI workspace can be created.")
      }
      if (Files.isSymbolicLink(parent)) {
        throw new IllegalStateException("Refusing to operate through a symlink at ${parent}.")
      }
    } else {
      // Still inside the workspace subtree (dir != root, and dir startsWith root per the public
      // method's check) — safe to recurse and create/verify intermediate levels ourselves, with a
      // fresh symlink check at every level rather than skipping already-existing ones.
      ensureWithinRoot(root, dir.parent)
    }
    createDirectory(dir)
    applyAndVerify(dir, SecretFileKind.EXECUTABLE)
  }

  void createFileWithPermissions(Path path, SecretFileKind kind) {
    createFileWithPermissions(path, kind, path.fileSystem.supportedFileAttributeViews())
  }

  void createFileWithPermissions(Path path, SecretFileKind kind, Set<String> supportedViews) {
    if (supportedViews.contains('posix')) {
      Files.createFile(path, PosixFilePermissions.asFileAttribute(permissionsFor(kind)))
      verifyPosix(path, kind)
      return
    }
    if (supportedViews.contains('acl')) {
      Files.createFile(path)
      aclAdapter.applyOwnerOnly(path)
      aclAdapter.verifyOwnerOnly(path)
      return
    }
    throw new IllegalStateException(
        "Neither POSIX permissions nor ACLs are supported for ${path}; refusing to write a secret-bearing file.")
  }

  void applyAndVerify(Path path, SecretFileKind kind) {
    applyAndVerify(path, kind, path.fileSystem.supportedFileAttributeViews())
  }

  void applyAndVerify(Path path, SecretFileKind kind, Set<String> supportedViews) {
    if (supportedViews.contains('posix')) {
      Files.setPosixFilePermissions(path, permissionsFor(kind))
      verifyPosix(path, kind)
      return
    }
    if (supportedViews.contains('acl')) {
      aclAdapter.applyOwnerOnly(path)
      aclAdapter.verifyOwnerOnly(path)
      return
    }
    throw new IllegalStateException("Neither POSIX permissions nor ACLs are supported for ${path}.")
  }

  void verifyNoSymlinksInPath(Path root, Path candidate) {
    Path normalizedRoot = root.toAbsolutePath().normalize()
    Path normalizedCandidate = candidate.toAbsolutePath().normalize()
    if (!normalizedCandidate.startsWith(normalizedRoot)) {
      throw new IllegalArgumentException("${candidate} is not inside the AI workspace root ${root}.")
    }
    if (Files.isSymbolicLink(normalizedRoot)) {
      throw new IllegalStateException("Refusing to operate through a symlink at ${normalizedRoot}.")
    }
    Path current = normalizedRoot
    Path relative = normalizedRoot.relativize(normalizedCandidate)
    for (Path segment : relative) {
      current = current.resolve(segment)
      if (Files.isSymbolicLink(current)) {
        throw new IllegalStateException("Refusing to operate through a symlink at ${current}.")
      }
    }
  }

  private static void createDirectory(Path dir) {
    Set<String> supportedViews = dir.fileSystem.supportedFileAttributeViews()
    if (supportedViews.contains('posix')) {
      Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(EXECUTABLE_PERMISSIONS))
      return
    }
    if (supportedViews.contains('acl')) {
      Files.createDirectory(dir)
      return
    }
    throw new IllegalStateException(
        "Neither POSIX permissions nor ACLs are supported for ${dir}; refusing to create the AI workspace.")
  }

  private static Set<PosixFilePermission> permissionsFor(SecretFileKind kind) {
    kind == SecretFileKind.EXECUTABLE ? EXECUTABLE_PERMISSIONS : DATA_PERMISSIONS
  }

  private static void verifyPosix(Path path, SecretFileKind kind) {
    Set<PosixFilePermission> expected = permissionsFor(kind)
    Set<PosixFilePermission> actual = Files.getPosixFilePermissions(path)
    if (actual != expected) {
      throw new IllegalStateException("Expected permissions ${expected} on ${path} but found ${actual}.")
    }
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "se.alipsa.accounting.service.AiWorkspacePermissionsTest"`
Expected: PASS (10 tests; the two Unix-only symlink tests are skipped via `assumeTrue` on a Windows runner, if ever run there)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/service/SecretFileKind.groovy \
        app/src/main/groovy/se/alipsa/accounting/service/AclPermissionAdapter.groovy \
        app/src/main/groovy/se/alipsa/accounting/service/RealAclPermissionAdapter.groovy \
        app/src/main/groovy/se/alipsa/accounting/service/AiWorkspacePermissions.groovy \
        app/src/test/groovy/unit/se/alipsa/accounting/service/AiWorkspacePermissionsTest.groovy
git commit -m "lägger till AiWorkspacePermissions (fail-closed rättighetshantering)"
```

---

## Task 7: `SecretFileWriter` + `FileMover` + `AtomicSecretFileWriter`

**Files:**
- Create: `app/src/main/groovy/se/alipsa/accounting/service/SecretFileWriter.groovy`
- Create: `app/src/main/groovy/se/alipsa/accounting/service/FileMover.groovy`
- Create: `app/src/main/groovy/se/alipsa/accounting/service/AtomicSecretFileWriter.groovy`
- Test: `app/src/test/groovy/unit/se/alipsa/accounting/service/AtomicSecretFileWriterTest.groovy`

**Interfaces:**
- Consumes: `AiWorkspacePermissions` (Task 6, specifically `verifyNoSymlinksInPath`), `SecretFileKind` (Task 6).
- Produces: `interface SecretFileWriter { void write(Path root, Path target, byte[] content, SecretFileKind kind) }` — `root` is the AI workspace boundary, so the writer itself (not just its callers) verifies the whole path is symlink-free, immediately before creating the temp file, again immediately before the atomic move (the move is the actual commit point, so it gets its own fresh check), *and* once more immediately after the move, before the following `applyAndVerify` call — `applyAndVerify`'s chmod/ACL-apply has no portable no-follow form in `java.nio.file`, so it cannot itself refuse a symlink swapped in during the move; the third check narrows that window as far as is practical. Real implementation `AtomicSecretFileWriter` with a no-arg constructor and a `(AiWorkspacePermissions, FileMover)` constructor for tests. Later tasks (`AiWorkspaceService`, `AiAssistantLauncher`) construct `new AtomicSecretFileWriter()` and depend on the `SecretFileWriter.write(root, target, ...)` signature.

- [ ] **Step 1: Write the failing tests**

```groovy
package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertArrayEquals
import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assumptions.assumeTrue

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class AtomicSecretFileWriterTest {

  @TempDir
  Path tempDir

  @Test
  void writesContentWithDataPermissions() {
    assumeTrue(FileSystems.default.supportedFileAttributeViews().contains('posix'))
    AtomicSecretFileWriter writer = new AtomicSecretFileWriter()
    Path target = tempDir.resolve('.mcp.json')

    writer.write(tempDir, target, 'hello'.getBytes('UTF-8'), SecretFileKind.DATA)

    assertArrayEquals('hello'.getBytes('UTF-8'), Files.readAllBytes(target))
    assertEquals(PosixFilePermissions.fromString('rw-------'), Files.getPosixFilePermissions(target))
  }

  @Test
  void failedMoveLeavesOriginalFileUntouchedAndCleansUpTheTempFile() {
    Path target = tempDir.resolve('.mcp.json')
    Files.write(target, 'original'.getBytes('UTF-8'))
    FileMover failingMover = { Path from, Path to -> throw new java.io.IOException('simulated move failure') } as FileMover
    AtomicSecretFileWriter writer = new AtomicSecretFileWriter(new AiWorkspacePermissions(), failingMover)

    assertThrows(java.io.IOException) {
      writer.write(tempDir, target, 'new content'.getBytes('UTF-8'), SecretFileKind.DATA)
    }

    assertArrayEquals('original'.getBytes('UTF-8'), Files.readAllBytes(target))
    Files.newDirectoryStream(tempDir, '.mcp.json.tmp-*').withCloseable { stream ->
      assert !stream.iterator().hasNext()
    }
  }

  @Test
  void atomicMoveNotSupportedIsSurfacedAsALaunchBlockingError() {
    FileMover unsupportedMover = { Path from, Path to ->
      throw new AtomicMoveNotSupportedException(from.toString(), to.toString(), 'simulated')
    } as FileMover
    AtomicSecretFileWriter writer = new AtomicSecretFileWriter(new AiWorkspacePermissions(), unsupportedMover)
    Path target = tempDir.resolve('.mcp.json')

    assertThrows(IllegalStateException) {
      writer.write(tempDir, target, 'content'.getBytes('UTF-8'), SecretFileKind.DATA)
    }
  }

  @Test
  void refusesToWriteThroughASymlinkedTarget() {
    assumeTrue(!System.getProperty('os.name', '').toLowerCase(Locale.ROOT).contains('win'))
    Path outside = tempDir.resolve('outside.json')
    Path linkedTarget = tempDir.resolve('.mcp.json')
    Files.createSymbolicLink(linkedTarget, outside)
    AtomicSecretFileWriter writer = new AtomicSecretFileWriter()

    assertThrows(IllegalStateException) {
      writer.write(tempDir, linkedTarget, 'content'.getBytes('UTF-8'), SecretFileKind.DATA)
    }

    assert !Files.exists(outside)
  }

  @Test
  void detectsASymlinkSwappedInImmediatelyAfterTheMoveAndRefusesToApplyPermissionsThroughIt() {
    // Simulates a symlink-swap race won immediately after the atomic move commits, without
    // needing real concurrency: the fake FileMover does the real move, then plants a symlink at
    // the same path before returning, so write()'s post-move re-check has something to catch.
    assumeTrue(!System.getProperty('os.name', '').toLowerCase(Locale.ROOT).contains('win'))
    Path target = tempDir.resolve('.mcp.json')
    Path outside = tempDir.resolve('outside.json')
    Files.write(outside, 'sensitive-outside-content'.getBytes('UTF-8'))
    FileMover swappingMover = { Path from, Path to ->
      Files.move(from, to, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      Files.delete(to)
      Files.createSymbolicLink(to, outside)
    } as FileMover
    AtomicSecretFileWriter writer = new AtomicSecretFileWriter(new AiWorkspacePermissions(), swappingMover)

    assertThrows(IllegalStateException) {
      writer.write(tempDir, target, 'content'.getBytes('UTF-8'), SecretFileKind.DATA)
    }
  }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "se.alipsa.accounting.service.AtomicSecretFileWriterTest"`
Expected: FAIL — classes not found.

- [ ] **Step 3: Write the implementation**

```groovy
// app/src/main/groovy/se/alipsa/accounting/service/SecretFileWriter.groovy
package se.alipsa.accounting.service

import java.nio.file.Path

/** Writes a secret-bearing file atomically, with the correct permissions for its kind. `root` is the AI workspace boundary. */
interface SecretFileWriter {
  void write(Path root, Path target, byte[] content, SecretFileKind kind)
}
```

```groovy
// app/src/main/groovy/se/alipsa/accounting/service/FileMover.groovy
package se.alipsa.accounting.service

import java.nio.file.Path

/** Seam around the atomic rename step, so tests can simulate a failed move without a real filesystem race. */
interface FileMover {
  void move(Path from, Path to)
}
```

```groovy
// app/src/main/groovy/se/alipsa/accounting/service/AtomicSecretFileWriter.groovy
package se.alipsa.accounting.service

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Writes secret-bearing files via temp-file-then-atomic-move, with fail-closed
 * permission verification and a symlink-chain check before the temp file is
 * created, again immediately before the atomic move (the move is the real
 * commit point, so it gets its own fresh check rather than relying solely on
 * the earlier one), and once more immediately after the move, before
 * applyAndVerify()'s chmod/ACL-apply call — that call has no portable
 * no-follow form in java.nio.file (POSIX chmod always follows symlinks, and
 * there is no reachable lchmod), so it cannot refuse a symlink swapped in
 * during the move on its own; the post-move check narrows, without being
 * able to fully close, that TOCTOU window.
 */
final class AtomicSecretFileWriter implements SecretFileWriter {

  private final AiWorkspacePermissions permissions
  private final FileMover fileMover

  AtomicSecretFileWriter() {
    this(new AiWorkspacePermissions(), { Path from, Path to ->
      Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } as FileMover)
  }

  AtomicSecretFileWriter(AiWorkspacePermissions permissions, FileMover fileMover) {
    this.permissions = permissions
    this.fileMover = fileMover
  }

  @Override
  void write(Path root, Path target, byte[] content, SecretFileKind kind) {
    permissions.verifyNoSymlinksInPath(root, target)
    Path tempFile = target.parent.resolve("${target.fileName}.tmp-${UUID.randomUUID()}")
    try {
      permissions.createFileWithPermissions(tempFile, kind)
      Files.write(tempFile, content)
      permissions.verifyNoSymlinksInPath(root, target)
      try {
        fileMover.move(tempFile, target)
      } catch (AtomicMoveNotSupportedException exception) {
        throw new IllegalStateException(
            "Atomic write is not supported for ${target}; refusing to fall back to a non-atomic replace.", exception)
      }
      // Re-check immediately after the move too, not just before it: applyAndVerify()'s
      // chmod/ACL-apply call has no portable no-follow form in java.nio.file (POSIX chmod always
      // follows symlinks, and there is no reachable lchmod equivalent), so it cannot refuse a
      // symlink swapped in during the move on its own. This narrows — without being able to fully
      // eliminate — the TOCTOU window between the move and the permission-apply call.
      permissions.verifyNoSymlinksInPath(root, target)
      permissions.applyAndVerify(target, kind)
    } finally {
      Files.deleteIfExists(tempFile)
    }
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "se.alipsa.accounting.service.AtomicSecretFileWriterTest"`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/service/SecretFileWriter.groovy \
        app/src/main/groovy/se/alipsa/accounting/service/FileMover.groovy \
        app/src/main/groovy/se/alipsa/accounting/service/AtomicSecretFileWriter.groovy \
        app/src/test/groovy/unit/se/alipsa/accounting/service/AtomicSecretFileWriterTest.groovy
git commit -m "lägger till AtomicSecretFileWriter (atomära hemlighetsskrivningar)"
```

---

## Task 8: `AiWorkspacePaths`

**Files:**
- Create: `app/src/main/groovy/se/alipsa/accounting/service/AiWorkspacePaths.groovy`
- Test: `app/src/test/groovy/unit/se/alipsa/accounting/service/AiWorkspacePathsTest.groovy`

**Interfaces:**
- Consumes: `AiClient` (Task 1).
- Produces: `static Path configFile(Path workspace, AiClient client)`, `static Path instructionsFile(Path workspace, AiClient client)` (the accounting MCP skill for Claude, or the combined profile + skill `AGENTS.md` for the other clients), `static Path assistantProfileFile(Path workspace, AiClient client)` (root `CLAUDE.md` for Claude; the same `AGENTS.md` path for the other clients), and `static Path wrapperScript(Path workspace, AiClient client, String launchId, boolean windows)`.

- [ ] **Step 1: Write the failing test**

```groovy
package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals

import org.junit.jupiter.api.Test

import se.alipsa.accounting.domain.AiClient

import java.nio.file.Path
import java.nio.file.Paths

class AiWorkspacePathsTest {

  private final Path workspace = Paths.get('/workspace')

  @Test
  void resolvesConfigFileRelativeToWorkspace() {
    assertEquals(Paths.get('/workspace/.codex/config.toml'), AiWorkspacePaths.configFile(workspace, AiClient.CODEX))
  }

  @Test
  void resolvesInstructionsFileRelativeToWorkspace() {
    assertEquals(
        Paths.get('/workspace/.claude/skills/accounting/accounting-mcp.md'),
        AiWorkspacePaths.instructionsFile(workspace, AiClient.CLAUDE))
  }

  @Test
  void resolvesClaudeProfileAndCodexAgentsInstructionsAtTheWorkspaceRoot() {
    assertEquals(Paths.get('/workspace/CLAUDE.md'), AiWorkspacePaths.assistantProfileFile(workspace, AiClient.CLAUDE))
    assertEquals(Paths.get('/workspace/AGENTS.md'), AiWorkspacePaths.assistantProfileFile(workspace, AiClient.CODEX))
  }

  @Test
  void resolvesUnixWrapperScriptWithClientAndLaunchId() {
    assertEquals(
        Paths.get('/workspace/.launch-codex-abc123.sh'),
        AiWorkspacePaths.wrapperScript(workspace, AiClient.CODEX, 'abc123', false))
  }

  @Test
  void resolvesWindowsWrapperScriptWithCmdExtension() {
    assertEquals(
        Paths.get('/workspace/.launch-codex-abc123.cmd'),
        AiWorkspacePaths.wrapperScript(workspace, AiClient.CODEX, 'abc123', true))
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "se.alipsa.accounting.service.AiWorkspacePathsTest"`
Expected: FAIL — class not found.

- [ ] **Step 3: Write the implementation**

```groovy
package se.alipsa.accounting.service

import se.alipsa.accounting.domain.AiClient

import java.nio.file.Path

/** Resolves file paths within the AI workspace for a given client. */
final class AiWorkspacePaths {

  private AiWorkspacePaths() {
  }

  static Path configFile(Path workspace, AiClient client) {
    workspace.resolve(client.configRelativePath)
  }

  static Path instructionsFile(Path workspace, AiClient client) {
    workspace.resolve(client.instructionsRelativePath)
  }

  static Path assistantProfileFile(Path workspace, AiClient client) {
    client == AiClient.CLAUDE ? workspace.resolve('CLAUDE.md') : instructionsFile(workspace, client)
  }

  static Path wrapperScript(Path workspace, AiClient client, String launchId, boolean windows) {
    workspace.resolve(".launch-${client.binaryName}-${launchId}.${windows ? 'cmd' : 'sh'}")
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "se.alipsa.accounting.service.AiWorkspacePathsTest"`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/service/AiWorkspacePaths.groovy app/src/test/groovy/unit/se/alipsa/accounting/service/AiWorkspacePathsTest.groovy
git commit -m "lägger till AiWorkspacePaths"
```

---

## Task 9: `AiClientConfigWriter` + fixtures

**Files:**
- Create: `app/src/main/groovy/se/alipsa/accounting/service/AiClientConfigWriter.groovy`
- Create: `app/src/test/resources/ai-launcher/claude-mcp.json`
- Create: `app/src/test/resources/ai-launcher/kimi-mcp.json`
- Create: `app/src/test/resources/ai-launcher/codex-config.toml`
- Create: `app/src/test/resources/ai-launcher/vibe-config.toml`
- Test: `app/src/test/groovy/unit/se/alipsa/accounting/service/AiClientConfigWriterTest.groovy`

**Interfaces:**
- Consumes: `AiClient` (Task 1).
- Produces: `static String configContent(AiClient client, String endpoint, String token)`.

- [ ] **Step 1: Write the fixture files**

`app/src/test/resources/ai-launcher/claude-mcp.json`:
```
{"mcpServers":{"accounting":{"type":"http","url":"http://127.0.0.1:48652/mcp","headers":{"Authorization":"Bearer test-token-123"}}}}
```

`app/src/test/resources/ai-launcher/kimi-mcp.json`:
```
{"mcpServers":{"accounting":{"type":"http","url":"http://127.0.0.1:48652/mcp","headers":{"Authorization":"Bearer test-token-123"}}}}
```

`app/src/test/resources/ai-launcher/codex-config.toml`:
```
[mcp_servers.accounting]
url = "http://127.0.0.1:48652/mcp"
bearer_token_env_var = "ACCOUNTING_MCP_TOKEN"
```

`app/src/test/resources/ai-launcher/vibe-config.toml` (array-of-tables `[[mcp_servers]]` with `name`/`transport`/`url` — confirmed against Mistral's own Vibe MCP docs at https://docs.mistral.ai/vibe/code/cli/mcp-servers; an earlier draft used the wrong `[mcp_servers.accounting]` table form, which Vibe does not recognize):
```
[[mcp_servers]]
name = "accounting"
transport = "http"
url = "http://127.0.0.1:48652/mcp"
headers = { Authorization = "Bearer test-token-123" }
```

(Each file must end with exactly one trailing newline — the way any normal editor save produces.)

- [ ] **Step 2: Write the failing test**

```groovy
package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse

import org.junit.jupiter.api.Test

import se.alipsa.accounting.domain.AiClient

class AiClientConfigWriterTest {

  private static final String ENDPOINT = 'http://127.0.0.1:48652/mcp'
  private static final String TOKEN = 'test-token-123'

  @Test
  void claudeConfigMatchesFixture() {
    assertEquals(fixture('claude-mcp.json'), AiClientConfigWriter.configContent(AiClient.CLAUDE, ENDPOINT, TOKEN))
  }

  @Test
  void kimiConfigMatchesFixture() {
    assertEquals(fixture('kimi-mcp.json'), AiClientConfigWriter.configContent(AiClient.KIMI, ENDPOINT, TOKEN))
  }

  @Test
  void codexConfigMatchesFixtureAndNeverContainsTheLiteralToken() {
    String content = AiClientConfigWriter.configContent(AiClient.CODEX, ENDPOINT, TOKEN)
    assertEquals(fixture('codex-config.toml'), content)
    assertFalse(content.contains(TOKEN))
  }

  @Test
  void vibeConfigMatchesFixture() {
    assertEquals(fixture('vibe-config.toml'), AiClientConfigWriter.configContent(AiClient.VIBE, ENDPOINT, TOKEN))
  }

  private static String fixture(String name) {
    AiClientConfigWriterTest.getResourceAsStream("/ai-launcher/${name}").withCloseable { it.getText('UTF-8') }
  }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests "se.alipsa.accounting.service.AiClientConfigWriterTest"`
Expected: FAIL — `AiClientConfigWriter` class not found.

- [ ] **Step 4: Write the implementation**

```groovy
package se.alipsa.accounting.service

import se.alipsa.accounting.domain.AiClient

import groovy.json.JsonOutput

/** Produces the exact config-file text written for each AI client. See design spec, "Workspace layout". */
final class AiClientConfigWriter {

  private AiClientConfigWriter() {
  }

  static String configContent(AiClient client, String endpoint, String token) {
    switch (client) {
      case AiClient.CLAUDE:
      case AiClient.KIMI:
        return bearerJson(endpoint, token)
      case AiClient.CODEX:
        return codexToml(endpoint)
      case AiClient.VIBE:
        return vibeToml(endpoint, token)
      default:
        throw new IllegalArgumentException("Unknown AI client: ${client}")
    }
  }

  private static String bearerJson(String endpoint, String token) {
    Map<String, Object> config = [
        mcpServers: [
            accounting: [
                type   : 'http',
                url    : endpoint,
                headers: [Authorization: "Bearer ${token}".toString()]
            ]
        ]
    ]
    JsonOutput.toJson(config) + '\n'
  }

  private static String codexToml(String endpoint) {
    """[mcp_servers.accounting]
url = "${endpoint}"
bearer_token_env_var = "ACCOUNTING_MCP_TOKEN"
""".toString()
  }

  private static String vibeToml(String endpoint, String token) {
    // Array-of-tables [[mcp_servers]] with name/transport/url is Vibe's documented schema
    // (docs.mistral.ai/vibe/code/cli/mcp-servers) — distinct from Codex's [mcp_servers.<name>] table form.
    """[[mcp_servers]]
name = "accounting"
transport = "http"
url = "${endpoint}"
headers = { Authorization = "Bearer ${token}" }
""".toString()
  }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "se.alipsa.accounting.service.AiClientConfigWriterTest"`
Expected: PASS (4 tests). If a fixture mismatch shows up in the diff output, fix the fixture file to match — the implementation above is the source of truth for the exact bytes, the fixtures just pin it against regressions.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/service/AiClientConfigWriter.groovy \
        app/src/test/resources/ai-launcher/ \
        app/src/test/groovy/unit/se/alipsa/accounting/service/AiClientConfigWriterTest.groovy
git commit -m "lägger till AiClientConfigWriter med fixturer"
```

---

## Task 10: `TerminalCommandBuilder`

**Files:**
- Create: `app/src/main/groovy/se/alipsa/accounting/service/TerminalCommandBuilder.groovy`
- Test: `app/src/test/groovy/unit/se/alipsa/accounting/service/TerminalCommandBuilderTest.groovy`

**Interfaces:**
- Consumes: `TerminalAdapterKind` (Task 2), `ProcessArgumentEscaping` (Task 3).
- Produces: `static List<String> commandFor(TerminalAdapterKind kind, Path executable, Path workspace, Path script)`. For `WINDOWS_TERMINAL`, the `-d <workspace>` and `/c <script>` arguments are returned pre-wrapped in a literal `"..."` pair, not bare — see the implementation's doc comment for why bare paths are unsafe here even though `ProcessBuilder` auto-quotes array elements containing spaces. It also throws `IllegalArgumentException` — fail-closed, not a residual/accepted gap — if `workspace` or `script` contains `&`, `|`, `<`, `>`, or `^`: quoting alone cannot guarantee `cmd.exe`'s own command-line grammar won't reinterpret one of those as a structural operator, so a path containing one is refused outright rather than launched and hoped to behave. `AiAssistantLauncher` (Task 14) calls this *before* writing the wrapper script, specifically so a refusal here never leaves a secret-bearing file behind. Also produces `static void rejectUnsafeWorkspacePathForWindowsTerminal(Path workspace)`, exposing the same workspace-only check standalone — for a caller that needs to fail closed on an unsafe *workspace* path before the per-launch script path even exists (`AiAssistantLauncher.validatePreflight`, Task 14, called before a client config file is written, not just before the wrapper script). The five-character unsafe set itself is `ProcessArgumentEscaping.UNSAFE_WINDOWS_COMMAND_CHARACTERS` (Task 3), not a separate list defined here — `LaunchWrapperScript` (Task 11) needs the identical set for values embedded inside the `.cmd` file's own content, not just this class's outer `wt.exe`/`cmd.exe` invocation, so both share one definition rather than risking the two lists drifting apart.

- [ ] **Step 1: Write the failing test**

```groovy
package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.Test

import se.alipsa.accounting.domain.TerminalAdapterKind

import java.nio.file.Path
import java.nio.file.Paths

class TerminalCommandBuilderTest {

  private final Path executable = Paths.get('/usr/bin/gnome-terminal')
  private final Path workspace = Paths.get('/home/per/.local/share/alipsa-accounting/ai-workspace')
  private final Path script = Paths.get('/home/per/.local/share/alipsa-accounting/ai-workspace/.launch-codex-abc.sh')

  @Test
  void gnomeTerminalUsesDoubleDashSeparator() {
    assertEquals(
        [executable.toString(), '--', script.toString()],
        TerminalCommandBuilder.commandFor(TerminalAdapterKind.GNOME_TERMINAL, executable, workspace, script))
  }

  @Test
  void konsoleAndXtermUseDashE() {
    assertEquals(
        [executable.toString(), '-e', script.toString()],
        TerminalCommandBuilder.commandFor(TerminalAdapterKind.KONSOLE, executable, workspace, script))
    assertEquals(
        [executable.toString(), '-e', script.toString()],
        TerminalCommandBuilder.commandFor(TerminalAdapterKind.XTERM, executable, workspace, script))
  }

  @Test
  void windowsTerminalExplicitlyInvokesCmdWithDelayedExpansionOff() {
    Path wtExe = Paths.get('C:/Users/per/AppData/Local/Microsoft/WindowsApps/wt.exe')
    Path winScript = Paths.get('C:/Users/per/AppData/Roaming/Alipsa/Accounting/ai-workspace/.launch-codex-abc.cmd')

    assertEquals(
        [wtExe.toString(), '-d', "\"${workspace}\"".toString(), 'cmd.exe', '/v:off', '/c', "\"${winScript}\"".toString()],
        TerminalCommandBuilder.commandFor(TerminalAdapterKind.WINDOWS_TERMINAL, wtExe, workspace, winScript))
  }

  @Test
  void windowsTerminalQuotesAWorkspacePathContainingSpaces() {
    Path wtExe = Paths.get('C:/Users/per/AppData/Local/Microsoft/WindowsApps/wt.exe')
    Path spacedWorkspace = Paths.get('C:/Users/Per Nyfelt/AppData/Roaming/Alipsa/Accounting/ai-workspace')
    Path winScript = spacedWorkspace.resolve('.launch-codex-abc.cmd')

    List<String> command = TerminalCommandBuilder.commandFor(TerminalAdapterKind.WINDOWS_TERMINAL, wtExe, spacedWorkspace, winScript)

    assertEquals("\"${spacedWorkspace}\"".toString(), command[2])
    assertEquals("\"${winScript}\"".toString(), command[6])
  }

  @Test
  void windowsTerminalRejectsWorkspaceOrScriptPathsContainingAnyCmdMetacharacter() {
    // Quoting alone cannot guarantee cmd.exe's own command-line grammar won't reinterpret one of
    // these as a structural operator (see TerminalCommandBuilder's doc comment), so a path
    // containing any of them is refused outright — for both the workspace and the script
    // argument, and for all five characters, not just one.
    Path wtExe = Paths.get('C:/Users/per/AppData/Local/Microsoft/WindowsApps/wt.exe')
    ['&', '|', '<', '>', '^'].each { String unsafe ->
      Path unsafeWorkspace = Paths.get("C:/Users/Per ${unsafe} Nyfelt/ai-workspace")
      Path scriptUnderUnsafeWorkspace = unsafeWorkspace.resolve('.launch-codex-abc.cmd')
      assertThrows(IllegalArgumentException) {
        TerminalCommandBuilder.commandFor(TerminalAdapterKind.WINDOWS_TERMINAL, wtExe, unsafeWorkspace, scriptUnderUnsafeWorkspace)
      }

      Path safeWorkspace = Paths.get('C:/Users/Per Nyfelt/ai-workspace')
      Path unsafeScript = safeWorkspace.resolve(".launch-codex-abc${unsafe}.cmd")
      assertThrows(IllegalArgumentException) {
        TerminalCommandBuilder.commandFor(TerminalAdapterKind.WINDOWS_TERMINAL, wtExe, safeWorkspace, unsafeScript)
      }
    }
  }

  @Test
  void terminalAppComposesShellQuotingInsideAppleScriptEscaping() {
    Path osascript = Paths.get('/usr/bin/osascript')
    Path scriptWithSpace = Paths.get('/Users/per/Library/Application Support/AlipsaAccounting/ai-workspace/.launch-codex-abc.sh')

    List<String> command = TerminalCommandBuilder.commandFor(TerminalAdapterKind.TERMINAL_APP, osascript, workspace, scriptWithSpace)

    assertEquals(osascript.toString(), command[0])
    assertEquals('-e', command[1])
    assertTrue(command[2].startsWith('tell application "Terminal" to do script "'))
    assertTrue(command[2].contains(scriptWithSpace.toString()))
  }

  @Test
  void rejectUnsafeWorkspacePathForWindowsTerminalChecksTheWorkspaceAloneWithoutNeedingAScript() {
    // Proves the workspace-only check works standalone, independent of commandFor() — this is
    // what AiAssistantLauncher.validatePreflight() (Task 14) calls before a per-launch script
    // path even exists yet.
    Path unsafeWorkspace = Paths.get('C:/Users/Per & Nyfelt/ai-workspace')
    assertThrows(IllegalArgumentException) {
      TerminalCommandBuilder.rejectUnsafeWorkspacePathForWindowsTerminal(unsafeWorkspace)
    }

    Path safeWorkspace = Paths.get('C:/Users/Per Nyfelt/ai-workspace')
    TerminalCommandBuilder.rejectUnsafeWorkspacePathForWindowsTerminal(safeWorkspace)
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "se.alipsa.accounting.service.TerminalCommandBuilderTest"`
Expected: FAIL — class not found.

- [ ] **Step 3: Write the implementation**

```groovy
package se.alipsa.accounting.service

import se.alipsa.accounting.domain.TerminalAdapterKind
import se.alipsa.accounting.support.ProcessArgumentEscaping

import java.nio.file.Path

/**
 * Builds the exact process argument list for each known terminal adapter
 * kind. See design spec, "Terminal adapters".
 *
 * For {@code WINDOWS_TERMINAL}, the {@code -d <workspace>} and {@code /c
 * <script>} arguments are returned already wrapped in a literal {@code "..."}
 * pair, rather than bare. This matters even though {@code ProcessBuilder}
 * already auto-quotes array elements containing spaces for the
 * {@code CreateProcess} call that spawns {@code wt.exe}: Windows processes
 * always receive one raw command-line string (there is no real argv), so
 * {@code wt.exe} has to re-parse that string itself to find its own flags,
 * then re-serialize whatever follows into a *new* one-line command string to
 * hand off to {@code cmd.exe}. Whether that second, wt.exe-owned
 * reconstruction re-quotes a bare path containing spaces is not something
 * this codebase controls or can verify without a Windows machine — so instead
 * of relying on it, the path is pre-quoted here as a single literal token.
 * The JDK's own Windows {@code ProcessBuilder} implementation recognizes an
 * argument that is already fully wrapped in one matching pair of double
 * quotes and passes it through unmodified rather than re-quoting it, so this
 * does not become doubly escaped. Since NTFS forbids {@code "} in path names,
 * wrapping a real path in quotes can never itself produce an ambiguous or
 * malformed token. This also happens to be `cmd /?`'s own documented
 * quote-preserving form for running a single quoted executable name with no
 * further arguments, which is exactly this invocation's shape.
 *
 * Quoting alone does not close every risk, though: `cmd.exe`'s command-line
 * grammar treats `&`, `|`, `<`, `>`, `^` as structural even inside some of
 * its fallback quote-handling paths, and NTFS (unlike `"`) does not forbid
 * those characters in path names — most plausibly reachable via an unusual
 * Windows account/profile name. Rather than accept that as a residual gap,
 * `commandFor` refuses outright (`IllegalArgumentException`) to build a
 * `WINDOWS_TERMINAL` command line whose workspace or script path contains
 * any of them: this keeps the class fail-closed, consistent with the rest
 * of the AI workspace's security design, instead of launching through a
 * path it cannot guarantee `cmd.exe` will parse safely. `AiAssistantLauncher`
 * (Task 14) calls this before writing the wrapper script, so a refusal here
 * never leaves a secret-bearing file behind.
 */
final class TerminalCommandBuilder {

  private TerminalCommandBuilder() {
  }

  static List<String> commandFor(TerminalAdapterKind kind, Path executable, Path workspace, Path script) {
    switch (kind) {
      case TerminalAdapterKind.GNOME_TERMINAL:
        return [executable.toString(), '--', script.toString()]
      case TerminalAdapterKind.KONSOLE:
        return [executable.toString(), '-e', script.toString()]
      case TerminalAdapterKind.XTERM:
        return [executable.toString(), '-e', script.toString()]
      case TerminalAdapterKind.WINDOWS_TERMINAL:
        rejectUnsafeCmdCharacters(workspace)
        rejectUnsafeCmdCharacters(script)
        return [executable.toString(), '-d', quoteForCmd(workspace), 'cmd.exe', '/v:off', '/c', quoteForCmd(script)]
      case TerminalAdapterKind.TERMINAL_APP:
        String quotedScript = ProcessArgumentEscaping.shellQuoteSingle(script.toString())
        String appleScriptSource = 'tell application "Terminal" to do script "' +
            ProcessArgumentEscaping.appleScriptEscape(quotedScript) + '"'
        return [executable.toString(), '-e', appleScriptSource]
      default:
        throw new IllegalArgumentException("Unknown terminal adapter kind: ${kind}")
    }
  }

  /**
   * Validates a workspace path in isolation, ahead of and independent from {@link #commandFor} —
   * for a caller that needs to fail closed on an unsafe {@code WINDOWS_TERMINAL} workspace path
   * *before* the per-launch script path even exists, e.g. before writing a client config file
   * (see {@code AiAssistantLauncher.validatePreflight}, Task 14). Checking the workspace path
   * alone is equivalent to also checking the eventual script path: the script's filename portion
   * is always safe by construction (client binary name + a UUID, both program-controlled), and
   * the workspace path is always its prefix.
   */
  static void rejectUnsafeWorkspacePathForWindowsTerminal(Path workspace) {
    rejectUnsafeCmdCharacters(workspace)
  }

  private static void rejectUnsafeCmdCharacters(Path path) {
    String value = path.toString()
    ProcessArgumentEscaping.UNSAFE_WINDOWS_COMMAND_CHARACTERS.each { String unsafe ->
      if (value.contains(unsafe)) {
        throw new IllegalArgumentException(
            "Refusing to launch via cmd.exe: ${path} contains '${unsafe}', which cmd.exe's " +
                'command-line grammar can misinterpret as a structural operator even when quoted.')
      }
    }
  }

  private static String quoteForCmd(Path path) {
    '"' + path.toString() + '"'
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "se.alipsa.accounting.service.TerminalCommandBuilderTest"`
Expected: PASS (7 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/service/TerminalCommandBuilder.groovy app/src/test/groovy/unit/se/alipsa/accounting/service/TerminalCommandBuilderTest.groovy
git commit -m "lägger till TerminalCommandBuilder"
```

---

## Task 11: `LaunchWrapperScript`

**Files:**
- Create: `app/src/main/groovy/se/alipsa/accounting/service/LaunchWrapperScript.groovy`
- Test: `app/src/test/groovy/unit/se/alipsa/accounting/service/LaunchWrapperScriptTest.groovy`

**Interfaces:**
- Consumes: `ProcessArgumentEscaping` (Task 3).
- Produces: `static String unixContent(Path workspace, Path binaryPath, Map<String, String> envVars)`, `static String windowsContent(Path workspace, Path binaryPath, Map<String, String> envVars)`. `envVars` is an ordered map of extra environment variables to `export`/`set` before the `cd`/invocation lines — empty for Claude/Kimi/Vibe (no secret, no extra var needed: Vibe discovers `.vibe/config.toml` project-locally, confirmed by Mistral's own configuration docs, so it must not be pointed at the AI workspace via `VIBE_HOME` — doing so would relocate the user's *entire* Vibe profile, not just MCP config; see Task 14), `[ACCOUNTING_MCP_TOKEN: token]` for Codex. A generic map (not a single `tokenOrNull`) is kept even though only one client currently needs an entry, since more than one client *can* need an env var and some of those wouldn't be secrets. Every value in `windowsContent(...)`'s output — the workspace path, the binary path, and each `envVars` value — is run through `ProcessArgumentEscaping.escapeForCmdScript` (Task 3), which fails closed on `&`, `|`, `<`, `>`, `^` as well as `"`, since none of those are safe to embed in a `.cmd` file's content even quoted.

- [ ] **Step 1: Write the failing test**

```groovy
package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.Test

import java.nio.file.Path
import java.nio.file.Paths

class LaunchWrapperScriptTest {

  private final Path workspace = Paths.get('/home/per/.local/share/alipsa-accounting/ai-workspace')
  private final Path binaryPath = Paths.get('/usr/local/bin/codex')

  @Test
  void unixWrapperExportsAGivenEnvVarAndUsesSingleQuoting() {
    String content = LaunchWrapperScript.unixContent(workspace, binaryPath, [ACCOUNTING_MCP_TOKEN: 'secret-token'])

    String expected = '#!/bin/sh\n' +
        "export ACCOUNTING_MCP_TOKEN='secret-token'\n" +
        "cd '${workspace}'\n" +
        "exec '${binaryPath}'\n"
    assertEquals(expected, content)
  }

  @Test
  void unixWrapperWithNoEnvVarsHasNoExportLine() {
    String content = LaunchWrapperScript.unixContent(workspace, binaryPath, [:])

    assertFalse(content.contains('export'))
    String expected = '#!/bin/sh\n' +
        "cd '${workspace}'\n" +
        "exec '${binaryPath}'\n"
    assertEquals(expected, content)
  }

  @Test
  void unixWrapperSupportsMultipleEnvVarsInInsertionOrder() {
    // Names are arbitrary examples exercising the generic map mechanism itself — this class
    // doesn't know about AiClient or any particular client's env vars.
    Map<String, String> envVars = new LinkedHashMap<>()
    envVars.FIRST_VAR = 'first-value'
    envVars.SECOND_VAR = 'second-value'
    String content = LaunchWrapperScript.unixContent(workspace, binaryPath, envVars)
    List<String> lines = content.split('\n', -1) as List<String>

    assertEquals("export FIRST_VAR='first-value'".toString(), lines[1])
    assertEquals("export SECOND_VAR='second-value'".toString(), lines[2])
  }

  @Test
  void unixWrapperNeutralizesCommandSubstitutionInBinaryPath() {
    Path malicious = Paths.get('/tmp/$(rm -rf ~)')
    String content = LaunchWrapperScript.unixContent(workspace, malicious, [:])

    assertTrue(content.contains("'" + malicious.toString() + "'"))
  }

  @Test
  void windowsWrapperMatchesExactTemplateOrdering() {
    Path winWorkspace = Paths.get('C:\\Users\\per\\AppData\\Roaming\\Alipsa\\Accounting\\ai-workspace')
    Path winBinary = Paths.get('C:\\Program Files\\codex\\codex.cmd')

    String content = LaunchWrapperScript.windowsContent(winWorkspace, winBinary, [ACCOUNTING_MCP_TOKEN: 'secret-token'])
    List<String> lines = content.split('\r\n', -1) as List<String>

    assertEquals('@echo off', lines[0])
    assertEquals('setlocal DisableDelayedExpansion', lines[1])
    assertEquals('set "ACCOUNTING_MCP_TOKEN=secret-token"', lines[2])
    assertEquals("cd /d \"${winWorkspace}\"".toString(), lines[3])
    assertEquals("\"${winBinary}\"".toString(), lines[4])
    assertFalse(content.contains('call '))
  }

  @Test
  void windowsWrapperWithNoEnvVarsHasNoSetLine() {
    Path winWorkspace = Paths.get('C:\\Users\\per\\AppData\\Roaming\\Alipsa\\Accounting\\ai-workspace')
    Path winBinary = Paths.get('C:\\Program Files\\claude\\claude.cmd')

    String content = LaunchWrapperScript.windowsContent(winWorkspace, winBinary, [:])
    List<String> lines = content.split('\r\n', -1) as List<String>

    assertEquals('@echo off', lines[0])
    assertEquals('setlocal DisableDelayedExpansion', lines[1])
    assertEquals("cd /d \"${winWorkspace}\"".toString(), lines[2])
    assertEquals("\"${winBinary}\"".toString(), lines[3])
    assertFalse(content.contains('set "'))
    assertFalse(content.contains('call '))
  }

  @Test
  void windowsWrapperDoubleExpansionRegressionStaysInertWithoutCall() {
    Path winWorkspace = Paths.get('C:\\Users\\per\\AppData\\Roaming\\Alipsa\\Accounting\\ai-workspace')
    Path variableShapedBinary = Paths.get('C:\\Program Files\\%SOMEVAR%\\tool.cmd')

    String content = LaunchWrapperScript.windowsContent(winWorkspace, variableShapedBinary, [:])

    assertTrue(content.contains('%%SOMEVAR%%'))
    assertFalse(content.contains('call '))
  }

  @Test
  void windowsContentRejectsACmdMetacharacterInTheBinaryPath() {
    // binaryPath is user-configurable (typed into Settings or PATH-detected) — unlike workspace,
    // it isn't validated anywhere upstream, so windowsContent()/escapeForCmdScript() (Task 3) must
    // be the one place that catches this.
    Path winWorkspace = Paths.get('C:\\Users\\per\\AppData\\Roaming\\Alipsa\\Accounting\\ai-workspace')
    ['&', '|', '<', '>', '^'].each { String unsafe ->
      Path unsafeBinary = Paths.get("C:\\Program Files\\codex ${unsafe} co\\codex.cmd")
      assertThrows(IllegalArgumentException) {
        LaunchWrapperScript.windowsContent(winWorkspace, unsafeBinary, [:])
      }
    }
  }

  @Test
  void windowsContentRejectsACmdMetacharacterInAnEnvVarValue() {
    Path winWorkspace = Paths.get('C:\\Users\\per\\AppData\\Roaming\\Alipsa\\Accounting\\ai-workspace')
    Path winBinary = Paths.get('C:\\Program Files\\codex\\codex.cmd')

    assertThrows(IllegalArgumentException) {
      LaunchWrapperScript.windowsContent(winWorkspace, winBinary, [ACCOUNTING_MCP_TOKEN: 'bad|token'])
    }
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "se.alipsa.accounting.service.LaunchWrapperScriptTest"`
Expected: FAIL — class not found.

- [ ] **Step 3: Write the implementation**

```groovy
package se.alipsa.accounting.service

import se.alipsa.accounting.support.ProcessArgumentEscaping

import java.nio.file.Path

/**
 * Renders the literal text of a per-launch wrapper script for Unix (.sh) and
 * Windows (.cmd). See design spec, "Launch wrapper script" — every escaping
 * and structural choice here (no `call`, `cd /d`, forced-off delayed
 * expansion, single-quoting) closes a specific, documented gap.
 */
final class LaunchWrapperScript {

  private LaunchWrapperScript() {
  }

  static String unixContent(Path workspace, Path binaryPath, Map<String, String> envVars) {
    StringBuilder content = new StringBuilder('#!/bin/sh\n')
    envVars.each { String name, String value ->
      content << "export ${name}=${ProcessArgumentEscaping.shellQuoteSingle(value)}\n"
    }
    content << "cd ${ProcessArgumentEscaping.shellQuoteSingle(workspace.toString())}\n"
    content << "exec ${ProcessArgumentEscaping.shellQuoteSingle(binaryPath.toString())}\n"
    content.toString()
  }

  static String windowsContent(Path workspace, Path binaryPath, Map<String, String> envVars) {
    StringBuilder content = new StringBuilder('@echo off\r\n')
    content << 'setlocal DisableDelayedExpansion\r\n'
    envVars.each { String name, String value ->
      content << "set \"${name}=${ProcessArgumentEscaping.escapeForCmdScript(value)}\"\r\n"
    }
    content << "cd /d \"${ProcessArgumentEscaping.escapeForCmdScript(workspace.toString())}\"\r\n"
    content << "\"${ProcessArgumentEscaping.escapeForCmdScript(binaryPath.toString())}\"\r\n"
    content.toString()
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "se.alipsa.accounting.service.LaunchWrapperScriptTest"`
Expected: PASS (9 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/service/LaunchWrapperScript.groovy app/src/test/groovy/unit/se/alipsa/accounting/service/LaunchWrapperScriptTest.groovy
git commit -m "lägger till LaunchWrapperScript"
```

---

## Task 12: `EnvironmentLookup`, `ExecutableProbe` + real implementation, `PathBinaryResolver`

**Files:**
- Create: `app/src/main/groovy/se/alipsa/accounting/service/EnvironmentLookup.groovy`
- Create: `app/src/main/groovy/se/alipsa/accounting/service/ExecutableProbe.groovy`
- Create: `app/src/main/groovy/se/alipsa/accounting/service/FileSystemExecutableProbe.groovy`
- Create: `app/src/main/groovy/se/alipsa/accounting/service/PathBinaryResolver.groovy`
- Test: `app/src/test/groovy/unit/se/alipsa/accounting/service/PathBinaryResolverTest.groovy`

**Interfaces:**
- Produces: `interface EnvironmentLookup { String getenv(String name) }`, `interface ExecutableProbe { boolean isExecutableFile(Path candidate) }`, `class PathBinaryResolver { PathBinaryResolver(EnvironmentLookup, ExecutableProbe); Path resolve(String binaryName) }`. `resolve(...)` tries the bare `binaryName` in each `PATH` directory first, then — if `environmentLookup.getenv('PATHEXT')` returns a non-blank value — each `;`-separated extension from it in order (e.g. `codex.CMD`, `codex.EXE`), matching how Windows itself resolves an extensionless command name; on Linux/macOS, `PATHEXT` is unset, so `environmentLookup.getenv('PATHEXT')` naturally returns `null` and behavior is unchanged (bare name only) — no separate "is this Windows" check is needed since the *same* injectable `EnvironmentLookup` seam this class already takes for `PATH` doubles as the Windows-detection/test seam for this. All `AiClient.binaryName` values (`claude`, `codex`, `kimi`, `vibe`) are extensionless, so without this, PATH-based auto-detection could never find a real Windows install, which normally exposes `*.exe`/`*.cmd`/`*.bat`, not a bare-named file. `AiWorkspaceService` (Task 13) constructs and uses `PathBinaryResolver`.

- [ ] **Step 1: Write the failing test**

```groovy
package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull

import org.junit.jupiter.api.Test

import java.nio.file.Path
import java.nio.file.Paths

class PathBinaryResolverTest {

  @Test
  void resolvesTheFirstExecutableMatchOnPath() {
    EnvironmentLookup environment = { String name -> name == 'PATH' ? '/usr/bin:/usr/local/bin' : null } as EnvironmentLookup
    ExecutableProbe probe = { Path candidate -> candidate.toString() == '/usr/local/bin/codex' } as ExecutableProbe
    PathBinaryResolver resolver = new PathBinaryResolver(environment, probe)

    assertEquals(Paths.get('/usr/local/bin/codex'), resolver.resolve('codex'))
  }

  @Test
  void returnsNullWhenNoDirectoryHasTheBinary() {
    EnvironmentLookup environment = { String name -> name == 'PATH' ? '/usr/bin' : null } as EnvironmentLookup
    ExecutableProbe probe = { Path candidate -> false } as ExecutableProbe
    PathBinaryResolver resolver = new PathBinaryResolver(environment, probe)

    assertNull(resolver.resolve('missing-binary'))
  }

  @Test
  void returnsNullWhenPathIsNotSet() {
    EnvironmentLookup environment = { String name -> null } as EnvironmentLookup
    ExecutableProbe probe = { Path candidate -> true } as ExecutableProbe
    PathBinaryResolver resolver = new PathBinaryResolver(environment, probe)

    assertNull(resolver.resolve('codex'))
  }

  @Test
  void resolvesAWindowsStyleBinaryViaPathextWhenTheBareNameDoesNotExist() {
    // Real Windows installs expose codex.cmd/codex.exe, never a bare "codex" file — PATHEXT is
    // what tells resolve() which extensions to also try, the same way Windows itself would.
    EnvironmentLookup environment = { String name ->
      if (name == 'PATH') { return '/windows-style/bin' }
      if (name == 'PATHEXT') { return '.COM;.EXE;.BAT;.CMD' }
      null
    } as EnvironmentLookup
    ExecutableProbe probe = { Path candidate -> candidate.toString() == '/windows-style/bin/codex.CMD' } as ExecutableProbe
    PathBinaryResolver resolver = new PathBinaryResolver(environment, probe)

    assertEquals(Paths.get('/windows-style/bin/codex.CMD'), resolver.resolve('codex'))
  }

  @Test
  void triesTheExtensionlessNameBeforeAnyPathextExtension() {
    EnvironmentLookup environment = { String name ->
      if (name == 'PATH') { return '/windows-style/bin' }
      if (name == 'PATHEXT') { return '.COM;.EXE;.BAT;.CMD' }
      null
    } as EnvironmentLookup
    // The probe would also accept codex.EXE, but the bare name must win since it's tried first.
    ExecutableProbe probe = { Path candidate ->
      candidate.toString() == '/windows-style/bin/codex' || candidate.toString() == '/windows-style/bin/codex.EXE'
    } as ExecutableProbe
    PathBinaryResolver resolver = new PathBinaryResolver(environment, probe)

    assertEquals(Paths.get('/windows-style/bin/codex'), resolver.resolve('codex'))
  }

  @Test
  void ignoresPathextWhenItIsNotSetLikeOnLinuxOrMacos() {
    EnvironmentLookup environment = { String name -> name == 'PATH' ? '/usr/bin' : null } as EnvironmentLookup
    ExecutableProbe probe = { Path candidate -> candidate.toString() == '/usr/bin/codex.CMD' } as ExecutableProbe
    PathBinaryResolver resolver = new PathBinaryResolver(environment, probe)

    assertNull(resolver.resolve('codex'))
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "se.alipsa.accounting.service.PathBinaryResolverTest"`
Expected: FAIL — classes not found.

- [ ] **Step 3: Write the implementation**

```groovy
// app/src/main/groovy/se/alipsa/accounting/service/EnvironmentLookup.groovy
package se.alipsa.accounting.service

/** Seam around System.getenv, so PATH-scanning logic is testable without the real environment. */
interface EnvironmentLookup {
  String getenv(String name)
}
```

```groovy
// app/src/main/groovy/se/alipsa/accounting/service/ExecutableProbe.groovy
package se.alipsa.accounting.service

import java.nio.file.Path

/** Seam around "is this an existing, executable file", so PATH-scanning logic is testable without a real filesystem. */
interface ExecutableProbe {
  boolean isExecutableFile(Path candidate)
}
```

```groovy
// app/src/main/groovy/se/alipsa/accounting/service/FileSystemExecutableProbe.groovy
package se.alipsa.accounting.service

import java.nio.file.Files
import java.nio.file.Path

final class FileSystemExecutableProbe implements ExecutableProbe {
  @Override
  boolean isExecutableFile(Path candidate) {
    Files.isRegularFile(candidate) && Files.isExecutable(candidate)
  }
}
```

```groovy
// app/src/main/groovy/se/alipsa/accounting/service/PathBinaryResolver.groovy
package se.alipsa.accounting.service

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolves a binary name to an absolute path by scanning PATH, via injectable
 * environment/filesystem seams. On Windows, an extensionless name like
 * {@code codex} never exists as a literal file — real installs expose
 * {@code codex.cmd}/{@code codex.exe} — so this also tries each extension
 * from the {@code PATHEXT} environment variable, the same mechanism Windows
 * itself uses to resolve a bare command name. The extensionless form is
 * still tried first, for every platform. Reading {@code PATHEXT} through the
 * same {@link EnvironmentLookup} seam already used for {@code PATH} means no
 * separate "is this Windows" check or seam is needed: on Linux/macOS,
 * {@code PATHEXT} is simply unset, so this falls back to the extensionless
 * probe exactly as before, and the Windows path is still fully testable on
 * any host OS by having the fake {@link EnvironmentLookup} return a value
 * for it.
 */
final class PathBinaryResolver {

  private final EnvironmentLookup environmentLookup
  private final ExecutableProbe executableProbe

  PathBinaryResolver(EnvironmentLookup environmentLookup, ExecutableProbe executableProbe) {
    this.environmentLookup = environmentLookup
    this.executableProbe = executableProbe
  }

  Path resolve(String binaryName) {
    String path = environmentLookup.getenv('PATH')
    if (!path) {
      return null
    }
    List<String> suffixes = candidateSuffixes()
    for (String directory : path.split(java.io.File.pathSeparator)) {
      if (!directory) {
        continue
      }
      for (String suffix : suffixes) {
        Path candidate = Paths.get(directory, binaryName + suffix)
        if (executableProbe.isExecutableFile(candidate)) {
          return candidate.toAbsolutePath().normalize()
        }
      }
    }
    null
  }

  private List<String> candidateSuffixes() {
    List<String> suffixes = ['']
    String pathext = environmentLookup.getenv('PATHEXT')
    if (pathext) {
      pathext.split(';').each { String extension ->
        if (extension?.trim()) {
          suffixes << extension.trim()
        }
      }
    }
    suffixes
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "se.alipsa.accounting.service.PathBinaryResolverTest"`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/service/EnvironmentLookup.groovy \
        app/src/main/groovy/se/alipsa/accounting/service/ExecutableProbe.groovy \
        app/src/main/groovy/se/alipsa/accounting/service/FileSystemExecutableProbe.groovy \
        app/src/main/groovy/se/alipsa/accounting/service/PathBinaryResolver.groovy \
        app/src/test/groovy/unit/se/alipsa/accounting/service/PathBinaryResolverTest.groovy
git commit -m "lägger till PathBinaryResolver"
```

---

## Task 13: `PurgeResult` + `AiWorkspaceService`

**Files:**
- Create: `app/src/main/groovy/se/alipsa/accounting/service/FileDeleter.groovy`
- Create: `app/src/main/groovy/se/alipsa/accounting/service/PurgeResult.groovy`
- Create: `app/src/main/groovy/se/alipsa/accounting/service/AiWorkspaceService.groovy`
- Test: `app/src/test/groovy/unit/se/alipsa/accounting/service/AiWorkspaceServiceTest.groovy`

**Interfaces:**
- Consumes: `AiClient` (Task 1), `TerminalAdapterKind` (Task 2), `AppPaths.aiWorkspaceDirectory()`/`AI_WORKSPACE_HOME_OVERRIDE_PROPERTY` (Task 4), `AiWorkspacePermissions` (Task 6, including `verifyNoSymlinksInPath`), `SecretFileWriter`/`AtomicSecretFileWriter` (Task 7 — note the `write(root, target, ...)` signature), `AiWorkspacePaths` (Task 8), `AiClientConfigWriter` (Task 9), `EnvironmentLookup`/`ExecutableProbe`/`FileSystemExecutableProbe`/`PathBinaryResolver` (Task 12).
- Produces: `interface FileDeleter { boolean deleteIfExists(Path path) }` — a seam around `Files.deleteIfExists`, so a test can make exactly one path's deletion fail without touching the real filesystem. `class PurgeResult { List<Path> removed; List<Path> failed; boolean isComplete() }`. `class AiWorkspaceService` with a no-arg constructor (real collaborators) and a `(AiWorkspacePermissions, SecretFileWriter, ExecutableProbe, EnvironmentLookup, FileDeleter)` constructor for tests — note this is a different shape than the `PathBinaryResolver`-based one sketched in Task 12's interface note; `AiWorkspaceService` builds its own `PathBinaryResolver` internally from the injected `ExecutableProbe`/`EnvironmentLookup`, because it also needs the bare `ExecutableProbe` directly for `isValidExecutable`. Methods: `void ensureWorkspace()`, `void refreshClientFiles(AiClient client, String endpoint, String token)`, `PurgeResult purgeAllSecrets()`, `Path detectBinaryPath(AiClient client)`, `Tuple2<TerminalAdapterKind, Path> detectTerminalAdapter()`, `boolean isValidExecutable(Path candidate)`. This is what `McpSettingsSection`, `McpServerLifecycle`, and `AiAssistantLauncherSection` (later tasks) all depend on.

- [ ] **Step 1: Write the failing test**

```groovy
package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue
import static org.junit.jupiter.api.Assumptions.assumeTrue

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.AiClient
import se.alipsa.accounting.domain.TerminalAdapterKind
import se.alipsa.accounting.support.AppPaths

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

class AiWorkspaceServiceTest {

  @TempDir
  Path tempDir

  private String previousOverride

  @BeforeEach
  void redirectWorkspaceToTempDir() {
    assumeTrue(FileSystems.default.supportedFileAttributeViews().contains('posix'))
    assumeTrue(!System.getProperty('os.name', '').toLowerCase(Locale.ROOT).contains('win'))
    assumeTrue(!System.getProperty('os.name', '').toLowerCase(Locale.ROOT).contains('mac'))
    previousOverride = System.getProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY)
    System.setProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY, tempDir.toString())
  }

  @AfterEach
  void restoreOverride() {
    if (previousOverride == null) {
      System.clearProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY)
    } else {
      System.setProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY, previousOverride)
    }
  }

  @Test
  void refreshClientFilesWritesConfigAndCombinedProfileAndSkillForCodex() {
    AiWorkspaceService service = new AiWorkspaceService()

    service.refreshClientFiles(AiClient.CODEX, 'http://127.0.0.1:48652/mcp', 'unused-for-codex')

    Path workspace = AppPaths.aiWorkspaceDirectory()
    Path configFile = workspace.resolve('.codex/config.toml')
    Path instructionsFile = workspace.resolve('AGENTS.md')
    assertTrue(Files.exists(configFile))
    assertTrue(configFile.text.contains('bearer_token_env_var = "ACCOUNTING_MCP_TOKEN"'))
    assertTrue(Files.exists(instructionsFile))
    assertTrue(instructionsFile.text.startsWith('# Alipsa Accounting Assistant'))
    assertTrue(instructionsFile.text.contains('# Accounting MCP Skill'))
  }

  @Test
  void refreshClientFilesWritesClaudeProfileAndSkillSeparately() {
    AiWorkspaceService service = new AiWorkspaceService()

    service.refreshClientFiles(AiClient.CLAUDE, 'http://127.0.0.1:48652/mcp', 'claude-token')

    Path workspace = AppPaths.aiWorkspaceDirectory()
    assertTrue(workspace.resolve('CLAUDE.md').text.startsWith('# Alipsa Accounting Assistant'))
    assertTrue(workspace.resolve('.claude/skills/accounting/accounting-mcp.md').text.startsWith('---'))
  }

  @Test
  void refreshClientFilesForOneClientDoesNotTouchAnotherClientsFiles() {
    AiWorkspaceService service = new AiWorkspaceService()
    service.refreshClientFiles(AiClient.CLAUDE, 'http://127.0.0.1:48652/mcp', 'claude-token')
    Path claudeConfig = AppPaths.aiWorkspaceDirectory().resolve('.mcp.json')
    String firstWrite = claudeConfig.text

    service.refreshClientFiles(AiClient.CODEX, 'http://127.0.0.1:48652/mcp', 'unused')

    assertEquals(firstWrite, claudeConfig.text)
  }

  @Test
  void purgeAllSecretsRemovesEveryClientConfigAndWrapperFile() {
    AiWorkspaceService service = new AiWorkspaceService()
    service.refreshClientFiles(AiClient.CLAUDE, 'http://127.0.0.1:48652/mcp', 'token-1')
    service.refreshClientFiles(AiClient.CODEX, 'http://127.0.0.1:48652/mcp', 'unused')
    Path workspace = AppPaths.aiWorkspaceDirectory()
    Path wrapper = workspace.resolve('.launch-claude-abc123.sh')
    Files.write(wrapper, 'stub'.getBytes('UTF-8'))

    PurgeResult result = service.purgeAllSecrets()

    assertTrue(result.complete)
    assertTrue(result.failed.empty)
    assertEquals(false, Files.exists(workspace.resolve('.mcp.json')))
    assertEquals(false, Files.exists(workspace.resolve('.codex/config.toml')))
    assertEquals(false, Files.exists(wrapper))
  }

  @Test
  void purgeAllSecretsOnAnEmptyWorkspaceReportsCompleteWithNothingRemoved() {
    AiWorkspaceService service = new AiWorkspaceService()

    PurgeResult result = service.purgeAllSecrets()

    assertTrue(result.complete)
    assertTrue(result.removed.empty)
  }

  @Test
  void purgeAllSecretsReportsOnlyTheOneFileThatFailedToDelete() {
    AiWorkspaceService fixtureService = new AiWorkspaceService()
    fixtureService.refreshClientFiles(AiClient.CLAUDE, 'http://127.0.0.1:48652/mcp', 'token-1')
    fixtureService.refreshClientFiles(AiClient.CODEX, 'http://127.0.0.1:48652/mcp', 'unused')
    Path workspace = AppPaths.aiWorkspaceDirectory()
    Path codexConfig = workspace.resolve('.codex/config.toml')

    FileDeleter flakyDeleter = { Path path ->
      if (path == codexConfig) {
        throw new java.io.IOException('simulated delete failure')
      }
      Files.deleteIfExists(path)
    } as FileDeleter
    AiWorkspaceService service = new AiWorkspaceService(
        new AiWorkspacePermissions(),
        new AtomicSecretFileWriter(),
        new FileSystemExecutableProbe(),
        { String name -> System.getenv(name) } as EnvironmentLookup,
        flakyDeleter)

    PurgeResult result = service.purgeAllSecrets()

    assertFalse(result.complete)
    assertEquals([codexConfig], result.failed)
    assertTrue(result.removed.contains(workspace.resolve('.mcp.json')))
    assertEquals(true, Files.exists(codexConfig))
  }

  @Test
  void detectBinaryPathReturnsNullWhenNothingOnPathMatches() {
    AiWorkspaceService service = new AiWorkspaceService(
        new AiWorkspacePermissions(),
        new AtomicSecretFileWriter(),
        new FileSystemExecutableProbe(),
        { String name -> '' } as EnvironmentLookup,
        { Path path -> Files.deleteIfExists(path) } as FileDeleter)

    assertNull(service.detectBinaryPath(AiClient.CODEX))
  }

  @Test
  void detectTerminalAdapterReturnsTheFirstMatchingKindForCurrentOs() {
    Path fakeGnomeTerminal = tempDir.resolve('gnome-terminal')
    Files.createFile(fakeGnomeTerminal)
    fakeGnomeTerminal.toFile().setExecutable(true)
    EnvironmentLookup env = { String name -> name == 'PATH' ? tempDir.toString() : null } as EnvironmentLookup
    AiWorkspaceService service = new AiWorkspaceService(
        new AiWorkspacePermissions(),
        new AtomicSecretFileWriter(),
        new FileSystemExecutableProbe(),
        env,
        { Path path -> Files.deleteIfExists(path) } as FileDeleter)

    Tuple2<TerminalAdapterKind, Path> detected = service.detectTerminalAdapter()

    assertEquals(TerminalAdapterKind.GNOME_TERMINAL, detected.v1)
    assertEquals(fakeGnomeTerminal.toAbsolutePath().normalize(), detected.v2)
  }

  @Test
  void isValidExecutableReflectsWhetherTheCandidateIsARealExecutableFile() {
    Path notExecutable = tempDir.resolve('not-executable')
    Files.createFile(notExecutable)
    AiWorkspaceService service = new AiWorkspaceService()

    assertFalse(service.isValidExecutable(tempDir.resolve('does-not-exist')))
    assertFalse(service.isValidExecutable(notExecutable))
  }
}
```

(Add `import static org.junit.jupiter.api.Assertions.assertFalse` to the existing static-import list at the top of this test class.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "se.alipsa.accounting.service.AiWorkspaceServiceTest"`
Expected: FAIL — classes not found. This is also the first real exercise of Task 5's classpath-resource change: if either `accounting-mcp.md` or `assistant-profile.md` were missing from the classpath, one of the first two tests would fail with `IllegalStateException` from the resource-missing check below, not just a missing-class error.

- [ ] **Step 3: Write the implementation**

```groovy
// app/src/main/groovy/se/alipsa/accounting/service/FileDeleter.groovy
package se.alipsa.accounting.service

import java.nio.file.Path

/** Seam around Files.deleteIfExists, so a test can make exactly one path's deletion fail. */
interface FileDeleter {
  boolean deleteIfExists(Path path)
}
```

```groovy
// app/src/main/groovy/se/alipsa/accounting/service/PurgeResult.groovy
package se.alipsa.accounting.service

import java.nio.file.Path

/** Outcome of a purgeAllSecrets() sweep: which files were removed, and which could not be. */
final class PurgeResult {

  final List<Path> removed
  final List<Path> failed

  PurgeResult(List<Path> removed, List<Path> failed) {
    this.removed = List.copyOf(removed)
    this.failed = List.copyOf(failed)
  }

  boolean isComplete() {
    failed.isEmpty()
  }
}
```

```groovy
// app/src/main/groovy/se/alipsa/accounting/service/AiWorkspaceService.groovy
package se.alipsa.accounting.service

import se.alipsa.accounting.domain.AiClient
import se.alipsa.accounting.domain.TerminalAdapterKind
import se.alipsa.accounting.support.AppPaths

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Owns the AI workspace directory: config/instructions writing, PATH-based
 * detection, and secret cleanup. See design spec, "Workspace layout" and
 * "Secret lifecycle". Every write and delete is preceded by a symlink-chain
 * check against the workspace root — directory creation/permission changes
 * and deletions never happen before that check, since doing so could
 * traverse or mutate through an attacker-planted symlink.
 */
final class AiWorkspaceService {

  private static final Logger log = Logger.getLogger(AiWorkspaceService.name)

  private final AiWorkspacePermissions permissions
  private final SecretFileWriter secretFileWriter
  private final ExecutableProbe executableProbe
  private final PathBinaryResolver pathBinaryResolver
  private final FileDeleter fileDeleter

  AiWorkspaceService() {
    this(
        new AiWorkspacePermissions(),
        new AtomicSecretFileWriter(),
        new FileSystemExecutableProbe(),
        { String name -> System.getenv(name) } as EnvironmentLookup,
        { Path path -> Files.deleteIfExists(path) } as FileDeleter
    )
  }

  AiWorkspaceService(
      AiWorkspacePermissions permissions,
      SecretFileWriter secretFileWriter,
      ExecutableProbe executableProbe,
      EnvironmentLookup environmentLookup,
      FileDeleter fileDeleter
  ) {
    this.permissions = permissions
    this.secretFileWriter = secretFileWriter
    this.executableProbe = executableProbe
    this.pathBinaryResolver = new PathBinaryResolver(environmentLookup, executableProbe)
    this.fileDeleter = fileDeleter
  }

  void ensureWorkspace() {
    Path workspace = AppPaths.aiWorkspaceDirectory()
    permissions.ensureDirectory(workspace, workspace)
  }

  void refreshClientFiles(AiClient client, String endpoint, String token) {
    Path workspace = AppPaths.aiWorkspaceDirectory()
    ensureWorkspace()

    Path configFile = AiWorkspacePaths.configFile(workspace, client)
    // Verify BEFORE creating/traversing the parent chain, and again right before the write
    // (AtomicSecretFileWriter.write also re-checks immediately before its own atomic move —
    // see Task 7 — so this is deliberate, cheap, layered defense, not redundant by accident).
    permissions.verifyNoSymlinksInPath(workspace, configFile)
    permissions.ensureDirectory(workspace, configFile.parent)
    permissions.verifyNoSymlinksInPath(workspace, configFile)
    String configText = AiClientConfigWriter.configContent(client, endpoint, token)
    secretFileWriter.write(workspace, configFile, configText.getBytes('UTF-8'), SecretFileKind.DATA)

    Path instructionsFile = AiWorkspacePaths.instructionsFile(workspace, client)
    byte[] skill = resourceBytes('/accounting-mcp.md')
    byte[] profile = resourceBytes('/assistant-profile.md')
    if (client == AiClient.CLAUDE) {
      writeDataFile(workspace, instructionsFile, skill)
      writeDataFile(workspace, AiWorkspacePaths.assistantProfileFile(workspace, client), profile)
    } else {
      byte[] combinedInstructions = (new String(profile, 'UTF-8') + '\n\n' + new String(skill, 'UTF-8')).getBytes('UTF-8')
      writeDataFile(workspace, instructionsFile, combinedInstructions)
    }
  }

  private void writeDataFile(Path workspace, Path file, byte[] content) {
    permissions.verifyNoSymlinksInPath(workspace, file)
    permissions.ensureDirectory(workspace, file.parent)
    permissions.verifyNoSymlinksInPath(workspace, file)
    secretFileWriter.write(workspace, file, content, SecretFileKind.DATA)
  }

  private static byte[] resourceBytes(String resourceName) {
    InputStream stream = AiWorkspaceService.getResourceAsStream(resourceName)
    if (stream == null) {
      throw new IllegalStateException("The ${resourceName} resource is missing from the classpath.")
    }
    try {
      stream.bytes
    } finally {
      stream.close()
    }
  }

  PurgeResult purgeAllSecrets() {
    Path workspace = AppPaths.aiWorkspaceDirectory()
    List<Path> removed = []
    List<Path> failed = []
    if (Files.isSymbolicLink(workspace)) {
      // Never open a directory stream on the workspace root itself without checking this first —
      // newDirectoryStream() follows a symlinked directory and would list/delete through it.
      failed << workspace
      return new PurgeResult(removed, failed)
    }
    if (Files.isDirectory(workspace)) {
      AiClient.values().each { AiClient client -> deleteIfSafe(workspace, workspace.resolve(client.configRelativePath), removed, failed) }
      try {
        Files.newDirectoryStream(workspace, '.launch-*').withCloseable { stream ->
          stream.each { Path wrapper -> deleteIfSafe(workspace, wrapper, removed, failed) }
        }
      } catch (Exception exception) {
        log.log(Level.WARNING, "Could not list wrapper scripts in ${workspace}", exception)
        failed << workspace.resolve('.launch-*')
      }
    }
    new PurgeResult(removed, failed)
  }

  private void deleteIfSafe(Path root, Path path, List<Path> removed, List<Path> failed) {
    try {
      if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        return
      }
      permissions.verifyNoSymlinksInPath(root, path)
      if (fileDeleter.deleteIfExists(path)) {
        removed << path
      }
    } catch (Exception exception) {
      log.log(Level.WARNING, "Could not delete ${path}", exception)
      failed << path
    }
  }

  Path detectBinaryPath(AiClient client) {
    pathBinaryResolver.resolve(client.binaryName)
  }

  Tuple2<TerminalAdapterKind, Path> detectTerminalAdapter() {
    for (TerminalAdapterKind kind : TerminalAdapterKind.forCurrentOs()) {
      Path resolved = pathBinaryResolver.resolve(kind.defaultBinaryName)
      if (resolved != null) {
        return new Tuple2<TerminalAdapterKind, Path>(kind, resolved)
      }
    }
    null
  }

  boolean isValidExecutable(Path candidate) {
    candidate != null && executableProbe.isExecutableFile(candidate)
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "se.alipsa.accounting.service.AiWorkspaceServiceTest"`
Expected: PASS (9 tests, on a Linux CI runner; the OS-name assumptions skip the suite cleanly elsewhere)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/service/FileDeleter.groovy \
        app/src/main/groovy/se/alipsa/accounting/service/PurgeResult.groovy \
        app/src/main/groovy/se/alipsa/accounting/service/AiWorkspaceService.groovy \
        app/src/test/groovy/unit/se/alipsa/accounting/service/AiWorkspaceServiceTest.groovy
git commit -m "lägger till AiWorkspaceService"
```

---

## Task 14: `ProcessRunner` + `AiAssistantLauncher`

**Files:**
- Create: `app/src/main/groovy/se/alipsa/accounting/service/ProcessRunner.groovy`
- Create: `app/src/main/groovy/se/alipsa/accounting/service/AiAssistantLauncher.groovy`
- Test: `app/src/test/groovy/unit/se/alipsa/accounting/service/AiAssistantLauncherTest.groovy`

**Interfaces:**
- Consumes: `AiClient` (Task 1), `TerminalAdapterKind` (Task 2), `AppPaths.aiWorkspaceDirectory()` (Task 4), `AiWorkspacePermissions`/`SecretFileKind` (Task 6), `SecretFileWriter`/`AtomicSecretFileWriter` (Task 7 — note `write(root, target, ...)`), `AiWorkspacePaths` (Task 8), `TerminalCommandBuilder` (Task 10), `LaunchWrapperScript` (Task 11 — note `Map<String,String> envVars`, not `tokenOrNull`), `ExecutableProbe`/`FileSystemExecutableProbe` (Task 12).
- Produces: `interface ProcessRunner { Process run(List<String> command, Path workingDirectory) }`. `class AiAssistantLauncher` with a no-arg constructor and a `(AiWorkspacePermissions, SecretFileWriter, ExecutableProbe, ProcessRunner)` constructor, method `void launch(AiClient client, Path binaryPath, TerminalAdapterKind adapterKind, Path adapterExecutable, String token)` — this now validates both `binaryPath` and `adapterExecutable` are real executable files *before* writing the wrapper or spawning anything, throwing `IllegalArgumentException` if not (this is the authoritative check; `AiWorkspaceService.isValidExecutable`, Task 13, additionally lets the UI, Task 17, give a friendlier per-field error before even calling `launch`). `launch()` also calls `permissions.ensureDirectory(workspace, workspace)` itself before writing, so it never depends on a caller having already created the workspace (e.g. via `AiWorkspaceService.refreshClientFiles()`) — it is self-sufficient and fails with a clear permission-checked error rather than a raw `NoSuchFileException` if called on its own. `launch()` also calls `TerminalCommandBuilder.commandFor(...)` (Task 10) *before* writing the wrapper script, not after — `commandFor` itself refuses (fail-closed) a Windows workspace/script path containing a `cmd.exe` metacharacter, and that refusal must happen before any secret-bearing file is written, not after. Also produces an instance method `void validatePreflight(TerminalAdapterKind adapterKind)` — a standalone, no-write, no-spawn preflight that delegates to `TerminalCommandBuilder.rejectUnsafeWorkspacePathForWindowsTerminal(...)` (Task 10) for the current AI workspace path when `adapterKind == WINDOWS_TERMINAL`, a no-op otherwise. `launch()`'s own `commandFor(...)` call is too late to protect a *client config file*, which `AiAssistantLauncherSection.doLaunch()` (Task 17) writes via `AiWorkspaceService.refreshClientFiles()` *before* ever calling `launch()` — `validatePreflight` exists specifically so that write can be gated on the same check, not just the wrapper script. `AiAssistantLauncherSection` (Task 17) depends on this exact `launch(...)` signature and on `validatePreflight`.

- [ ] **Step 1: Write the failing tests**

```groovy
package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue
import static org.junit.jupiter.api.Assumptions.assumeTrue

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.AiClient
import se.alipsa.accounting.domain.TerminalAdapterKind
import se.alipsa.accounting.support.AppPaths

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class AiAssistantLauncherTest {

  @TempDir
  Path tempDir

  private String previousOverride
  private final ExecutableProbe alwaysExecutable = { Path candidate -> true } as ExecutableProbe

  @BeforeEach
  void redirectWorkspaceToTempDir() {
    assumeTrue(FileSystems.default.supportedFileAttributeViews().contains('posix'))
    assumeTrue(!System.getProperty('os.name', '').toLowerCase(Locale.ROOT).contains('win'))
    previousOverride = System.getProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY)
    System.setProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY, tempDir.toString())
    Path workspace = AppPaths.aiWorkspaceDirectory()
    new AiWorkspacePermissions().ensureDirectory(workspace, workspace)
  }

  @AfterEach
  void restoreOverride() {
    if (previousOverride == null) {
      System.clearProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY)
    } else {
      System.setProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY, previousOverride)
    }
  }

  @Test
  void launchWritesAUniqueExecutableWrapperAndSpawnsTheAdapterCommand() {
    List<List<String>> recordedCommands = []
    ProcessRunner fakeRunner = { List<String> command, Path dir ->
      recordedCommands << command
      new ProcessBuilder(['true']).start()
    } as ProcessRunner
    AiAssistantLauncher launcher =
        new AiAssistantLauncher(new AiWorkspacePermissions(), new AtomicSecretFileWriter(), alwaysExecutable, fakeRunner)
    Path binaryPath = Paths.get('/usr/local/bin/codex')

    launcher.launch(AiClient.CODEX, binaryPath, TerminalAdapterKind.XTERM, Paths.get('/usr/bin/xterm'), 'secret-token')

    assertEquals(1, recordedCommands.size())
    List<String> command = recordedCommands[0]
    assertEquals('/usr/bin/xterm', command[0])
    assertEquals('-e', command[1])
    Path writtenScript = Paths.get(command[2])
    assertTrue(Files.exists(writtenScript))
    assertTrue(writtenScript.text.contains("export ACCOUNTING_MCP_TOKEN='secret-token'"))
  }

  @Test
  void launchForVibeSetsNoEnvironmentVariablesAtAll() {
    // Regression test: VIBE_HOME must never be set. Vibe discovers .vibe/config.toml
    // project-locally already (confirmed by Mistral's own configuration docs), and VIBE_HOME
    // relocates the user's *entire* Vibe profile — config, .env credentials, agents, logs,
    // skills — not just MCP config, which would hide their existing setup and break it.
    List<List<String>> recordedCommands = []
    ProcessRunner fakeRunner = { List<String> command, Path dir ->
      recordedCommands << command
      new ProcessBuilder(['true']).start()
    } as ProcessRunner
    AiAssistantLauncher launcher =
        new AiAssistantLauncher(new AiWorkspacePermissions(), new AtomicSecretFileWriter(), alwaysExecutable, fakeRunner)
    Path binaryPath = Paths.get('/usr/local/bin/vibe')

    launcher.launch(AiClient.VIBE, binaryPath, TerminalAdapterKind.XTERM, Paths.get('/usr/bin/xterm'), 'secret-token')

    Path writtenScript = Paths.get(recordedCommands[0][2])
    String content = writtenScript.text
    assertFalse(content.contains('VIBE_HOME'))
    assertFalse(content.contains('export '))
    assertFalse(content.contains('ACCOUNTING_MCP_TOKEN'))
  }

  @Test
  void twoLaunchesOfTheSameClientProduceDistinctWrapperFilenames() {
    List<List<String>> recordedCommands = []
    ProcessRunner fakeRunner = { List<String> command, Path dir ->
      recordedCommands << command
      new ProcessBuilder(['true']).start()
    } as ProcessRunner
    AiAssistantLauncher launcher =
        new AiAssistantLauncher(new AiWorkspacePermissions(), new AtomicSecretFileWriter(), alwaysExecutable, fakeRunner)
    Path binaryPath = Paths.get('/usr/local/bin/claude')

    launcher.launch(AiClient.CLAUDE, binaryPath, TerminalAdapterKind.XTERM, Paths.get('/usr/bin/xterm'), null)
    launcher.launch(AiClient.CLAUDE, binaryPath, TerminalAdapterKind.XTERM, Paths.get('/usr/bin/xterm'), null)

    assertEquals(2, recordedCommands.size())
    assertFalse(recordedCommands[0][2] == recordedCommands[1][2])
    assertTrue(Files.exists(Paths.get(recordedCommands[0][2])))
    assertTrue(Files.exists(Paths.get(recordedCommands[1][2])))
  }

  @Test
  void spawnFailureDeletesTheJustWrittenWrapperAndSurfacesTheOriginalError() {
    ProcessRunner failingRunner = { List<String> command, Path dir ->
      throw new java.io.IOException('simulated terminal-not-found failure')
    } as ProcessRunner
    AiAssistantLauncher launcher =
        new AiAssistantLauncher(new AiWorkspacePermissions(), new AtomicSecretFileWriter(), alwaysExecutable, failingRunner)
    Path binaryPath = Paths.get('/usr/local/bin/claude')

    assertThrows(java.io.IOException) {
      launcher.launch(AiClient.CLAUDE, binaryPath, TerminalAdapterKind.XTERM, Paths.get('/usr/bin/xterm'), null)
    }

    Path workspace = AppPaths.aiWorkspaceDirectory()
    Files.newDirectoryStream(workspace, '.launch-claude-*').withCloseable { stream ->
      assertFalse(stream.iterator().hasNext())
    }
  }

  @Test
  void launchRejectsANonExecutableBinaryPathBeforeWritingOrSpawningAnything() {
    ExecutableProbe rejectAll = { Path candidate -> false } as ExecutableProbe
    ProcessRunner runnerThatShouldNeverBeCalled = { List<String> command, Path dir ->
      throw new AssertionError('ProcessRunner should not be invoked when the binary path is invalid')
    } as ProcessRunner
    AiAssistantLauncher launcher =
        new AiAssistantLauncher(new AiWorkspacePermissions(), new AtomicSecretFileWriter(), rejectAll, runnerThatShouldNeverBeCalled)

    assertThrows(IllegalArgumentException) {
      launcher.launch(AiClient.CLAUDE, Paths.get('/does/not/exist'), TerminalAdapterKind.XTERM, Paths.get('/usr/bin/xterm'), null)
    }
  }

  @Test
  void launchCreatesTheWorkspaceDirectoryWhenItDoesNotAlreadyExist() {
    // Deliberately does NOT rely on the shared @BeforeEach's pre-created workspace — this proves
    // launch() is self-sufficient and doesn't assume a prior refreshClientFiles() call already
    // created the directory (see AiAssistantLauncher's class doc comment). Only the workspace
    // ROOT itself is left uncreated: freshHome (its parent) is created first, because
    // AiWorkspacePermissions.ensureDirectory() (Task 6) requires the workspace root's parent to
    // already exist — this class creates the workspace root and everything below it, never
    // anything above it, matching AppPaths.ensureDirectoryStructure()'s separate responsibility.
    Path freshHome = tempDir.resolve('fresh-home')
    Files.createDirectories(freshHome)
    System.setProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY, freshHome.toString())
    List<List<String>> recordedCommands = []
    ProcessRunner fakeRunner = { List<String> command, Path dir ->
      recordedCommands << command
      new ProcessBuilder(['true']).start()
    } as ProcessRunner
    AiAssistantLauncher launcher =
        new AiAssistantLauncher(new AiWorkspacePermissions(), new AtomicSecretFileWriter(), alwaysExecutable, fakeRunner)

    launcher.launch(AiClient.CLAUDE, Paths.get('/usr/local/bin/claude'), TerminalAdapterKind.XTERM, Paths.get('/usr/bin/xterm'), null)

    assertTrue(Files.isDirectory(AppPaths.aiWorkspaceDirectory()))
    assertEquals(1, recordedCommands.size())
  }

  @Test
  void launchRejectsAWindowsWorkspacePathContainingACmdMetacharacterBeforeWritingAnything() {
    // TerminalCommandBuilder.commandFor() (Task 10) refuses a Windows workspace/script path
    // containing &|<>^ outright — this proves launch() calls it BEFORE writing the wrapper
    // script, not after, so a refusal never leaves a secret-bearing file (the Codex wrapper
    // contains the plaintext token) behind on disk.
    Path unsafeHome = tempDir.resolve('unsafe & home')
    Files.createDirectories(unsafeHome)
    System.setProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY, unsafeHome.toString())
    Path workspace = AppPaths.aiWorkspaceDirectory()
    new AiWorkspacePermissions().ensureDirectory(workspace, workspace)
    ProcessRunner runnerThatShouldNeverBeCalled = { List<String> command, Path dir ->
      throw new AssertionError('ProcessRunner should not be invoked when the Windows command line cannot be built safely')
    } as ProcessRunner
    AiAssistantLauncher launcher =
        new AiAssistantLauncher(new AiWorkspacePermissions(), new AtomicSecretFileWriter(), alwaysExecutable, runnerThatShouldNeverBeCalled)

    assertThrows(IllegalArgumentException) {
      launcher.launch(AiClient.CODEX, Paths.get('/usr/local/bin/codex'), TerminalAdapterKind.WINDOWS_TERMINAL, Paths.get('/usr/bin/wt.exe'), 'secret-token')
    }

    Files.newDirectoryStream(workspace, '.launch-codex-*').withCloseable { stream ->
      assertFalse(stream.iterator().hasNext())
    }
  }

  @Test
  void validatePreflightRejectsAnUnsafeWindowsWorkspacePathWithoutWritingOrSpawningAnything() {
    // Standalone entry point for callers that need to fail closed before writing a client config
    // file, which happens earlier than the wrapper script launch() itself protects — see
    // AiAssistantLauncherSection.doLaunch() (Task 17).
    Path unsafeHome = tempDir.resolve('unsafe & home')
    Files.createDirectories(unsafeHome)
    System.setProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY, unsafeHome.toString())
    AiAssistantLauncher launcher = new AiAssistantLauncher()

    assertThrows(IllegalArgumentException) {
      launcher.validatePreflight(TerminalAdapterKind.WINDOWS_TERMINAL)
    }
  }

  @Test
  void validatePreflightIsANoOpForNonWindowsAdapterKindsAndForASafeWindowsWorkspacePath() {
    AiAssistantLauncher launcher = new AiAssistantLauncher()

    launcher.validatePreflight(TerminalAdapterKind.XTERM)
    launcher.validatePreflight(TerminalAdapterKind.WINDOWS_TERMINAL)
  }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "se.alipsa.accounting.service.AiAssistantLauncherTest"`
Expected: FAIL — classes not found.

- [ ] **Step 3: Write the implementation**

```groovy
// app/src/main/groovy/se/alipsa/accounting/service/ProcessRunner.groovy
package se.alipsa.accounting.service

import java.nio.file.Path

/** Seam around ProcessBuilder.start(), so launch logic is testable without spawning a real terminal. */
interface ProcessRunner {
  Process run(List<String> command, Path workingDirectory)
}
```

```groovy
// app/src/main/groovy/se/alipsa/accounting/service/AiAssistantLauncher.groovy
package se.alipsa.accounting.service

import se.alipsa.accounting.domain.AiClient
import se.alipsa.accounting.domain.TerminalAdapterKind
import se.alipsa.accounting.support.AppPaths

import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Writes a per-launch wrapper script and spawns the chosen terminal to run
 * it. See design spec, "Launch wrapper script" and "Wrapper naming and
 * cleanup timing". Validates both paths it's given are real executable
 * files *before* writing anything or spawning — a stale/malicious path must
 * never silently "succeed" by opening a terminal that then fails inside.
 * Also ensures the workspace directory itself exists before writing into
 * it — {@code launch()} does not assume a prior {@code refreshClientFiles()}
 * call already created it, so calling this class on its own fails with a
 * clear permission-checked directory creation rather than a raw
 * {@code NoSuchFileException} from deep inside the file writer. Builds the
 * terminal command line ({@code TerminalCommandBuilder.commandFor}) before
 * writing the wrapper script, not after: that call fails closed for a
 * Windows workspace/script path containing a `cmd.exe` metacharacter, and a
 * refusal must never happen after the wrapper (which can hold a plaintext
 * secret for Codex) has already been written to disk.
 *
 * A successful launch deliberately leaves its wrapper script on disk
 * (rather than deleting it right after spawning): there is no reliable,
 * cross-platform signal that the spawned terminal has finished reading the
 * script, and deleting a file a terminal (especially `cmd.exe` on Windows)
 * may still have open could break the launch outright. This trades a
 * bounded plaintext-secret-on-disk window — closed by the next
 * {@code purgeAllSecrets()} (token rotation or app shutdown) — for launch
 * reliability. See design spec, "Secret lifecycle".
 */
final class AiAssistantLauncher {

  private static final Logger log = Logger.getLogger(AiAssistantLauncher.name)

  private final AiWorkspacePermissions permissions
  private final SecretFileWriter secretFileWriter
  private final ExecutableProbe executableProbe
  private final ProcessRunner processRunner

  AiAssistantLauncher() {
    this(
        new AiWorkspacePermissions(),
        new AtomicSecretFileWriter(),
        new FileSystemExecutableProbe(),
        { List<String> command, Path dir -> new ProcessBuilder(command).directory(dir.toFile()).start() } as ProcessRunner
    )
  }

  AiAssistantLauncher(
      AiWorkspacePermissions permissions,
      SecretFileWriter secretFileWriter,
      ExecutableProbe executableProbe,
      ProcessRunner processRunner
  ) {
    this.permissions = permissions
    this.secretFileWriter = secretFileWriter
    this.executableProbe = executableProbe
    this.processRunner = processRunner
  }

  void launch(AiClient client, Path binaryPath, TerminalAdapterKind adapterKind, Path adapterExecutable, String token) {
    if (!executableProbe.isExecutableFile(binaryPath)) {
      throw new IllegalArgumentException("${binaryPath} is not an executable file.")
    }
    if (!executableProbe.isExecutableFile(adapterExecutable)) {
      throw new IllegalArgumentException("${adapterExecutable} is not an executable file.")
    }

    Path workspace = AppPaths.aiWorkspaceDirectory()
    permissions.ensureDirectory(workspace, workspace)
    boolean windows = adapterKind == TerminalAdapterKind.WINDOWS_TERMINAL
    String launchId = UUID.randomUUID().toString()
    Path script = AiWorkspacePaths.wrapperScript(workspace, client, launchId, windows)
    permissions.verifyNoSymlinksInPath(workspace, script)

    // Build the command line — and let it fail closed on an unsafe Windows path — BEFORE writing
    // the wrapper script, so a refusal here never leaves a secret-bearing file on disk.
    List<String> command = TerminalCommandBuilder.commandFor(adapterKind, adapterExecutable, workspace, script)

    // Vibe deliberately gets no env vars: it discovers .vibe/config.toml project-locally already
    // (confirmed by Mistral's own configuration docs), and must never be pointed at the AI
    // workspace via VIBE_HOME — that relocates the user's entire Vibe profile (config, .env
    // credentials, agents, logs, skills), not just MCP config, hiding their existing setup.
    Map<String, String> envVars = new LinkedHashMap<>()
    if (client == AiClient.CODEX) {
      envVars.ACCOUNTING_MCP_TOKEN = token
    }
    String content = windows
        ? LaunchWrapperScript.windowsContent(workspace, binaryPath, envVars)
        : LaunchWrapperScript.unixContent(workspace, binaryPath, envVars)
    secretFileWriter.write(workspace, script, content.getBytes('UTF-8'), SecretFileKind.EXECUTABLE)

    try {
      processRunner.run(command, workspace)
    } catch (Exception exception) {
      deleteWrapperBestEffort(script)
      throw exception
    }
  }

  /**
   * A standalone preflight: validates that a launch for {@code adapterKind} could proceed given
   * the current AI workspace path, without writing or spawning anything. {@code launch()}'s own
   * {@code TerminalCommandBuilder.commandFor(...)} call already fails closed on an unsafe Windows
   * workspace/script path, but only once {@code launch()} itself runs — too late for a caller
   * that writes a client config file (via {@code AiWorkspaceService.refreshClientFiles()}) before
   * calling {@code launch()}, as {@code AiAssistantLauncherSection.doLaunch()} (Task 17) does.
   * Calling this first closes that gap: the workspace path's own safety doesn't depend on which
   * client or wrapper script is involved, so it can be checked before either exists.
   */
  void validatePreflight(TerminalAdapterKind adapterKind) {
    if (adapterKind == TerminalAdapterKind.WINDOWS_TERMINAL) {
      TerminalCommandBuilder.rejectUnsafeWorkspacePathForWindowsTerminal(AppPaths.aiWorkspaceDirectory())
    }
  }

  private static void deleteWrapperBestEffort(Path script) {
    try {
      Files.deleteIfExists(script)
    } catch (Exception exception) {
      log.log(Level.WARNING, "Could not delete wrapper script ${script} after a failed launch.", exception)
    }
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "se.alipsa.accounting.service.AiAssistantLauncherTest"`
Expected: PASS (10 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/service/ProcessRunner.groovy \
        app/src/main/groovy/se/alipsa/accounting/service/AiAssistantLauncher.groovy \
        app/src/test/groovy/unit/se/alipsa/accounting/service/AiAssistantLauncherTest.groovy
git commit -m "lägger till AiAssistantLauncher"
```

---

## Task 15: `UserPreferencesService` additions

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/service/UserPreferencesService.groovy`
- Create: `app/src/test/groovy/unit/se/alipsa/accounting/service/UserPreferencesServiceTest.groovy`

**Interfaces:**
- Consumes: `AiClient` (Task 1), `TerminalAdapterKind` (Task 2).
- Produces: `String getAiBinaryPath(AiClient)`, `void setAiBinaryPath(AiClient, String)`, `TerminalAdapterKind getTerminalAdapterKind()`, `void setTerminalAdapterKind(TerminalAdapterKind)`, `String getTerminalPath()`, `void setTerminalPath(String)`. `AiAssistantLauncherSection` (Task 17) and `McpSettingsSection` (Task 18) depend on these.

- [ ] **Step 1: Write the failing test** (this is a new test file — no test previously existed for this class)

```groovy
package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import se.alipsa.accounting.domain.AiClient
import se.alipsa.accounting.domain.TerminalAdapterKind

import java.util.prefs.Preferences

class UserPreferencesServiceTest {

  private Preferences node
  private UserPreferencesService service

  @BeforeEach
  void setUp() {
    node = Preferences.userRoot().node("alipsa-accounting-test-${UUID.randomUUID()}")
    service = new UserPreferencesService(node)
  }

  @AfterEach
  void tearDown() {
    node.removeNode()
  }

  @Test
  void aiBinaryPathIsStoredPerClient() {
    assertNull(service.getAiBinaryPath(AiClient.CODEX))

    service.setAiBinaryPath(AiClient.CODEX, '/usr/local/bin/codex')

    assertEquals('/usr/local/bin/codex', service.getAiBinaryPath(AiClient.CODEX))
    assertNull(service.getAiBinaryPath(AiClient.CLAUDE))
  }

  @Test
  void clearingAiBinaryPathRemovesIt() {
    service.setAiBinaryPath(AiClient.CODEX, '/usr/local/bin/codex')

    service.setAiBinaryPath(AiClient.CODEX, '')

    assertNull(service.getAiBinaryPath(AiClient.CODEX))
  }

  @Test
  void terminalAdapterKindAndPathAreStoredTogether() {
    assertNull(service.getTerminalAdapterKind())
    assertNull(service.getTerminalPath())

    service.setTerminalAdapterKind(TerminalAdapterKind.XTERM)
    service.setTerminalPath('/usr/bin/xterm')

    assertEquals(TerminalAdapterKind.XTERM, service.getTerminalAdapterKind())
    assertEquals('/usr/bin/xterm', service.getTerminalPath())
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "se.alipsa.accounting.service.UserPreferencesServiceTest"`
Expected: FAIL — the new accessor methods don't exist yet.

- [ ] **Step 3: Write the implementation**

Add imports near the top of `UserPreferencesService.groovy`:

```groovy
import se.alipsa.accounting.domain.AiClient
import se.alipsa.accounting.domain.TerminalAdapterKind
```

Add new key constants next to `MCP_TOKEN_KEY`:

```groovy
  private static final String AI_BINARY_KEY_PREFIX = 'ai.launcher.binary.'
  private static final String AI_TERMINAL_ADAPTER_KIND_KEY = 'ai.launcher.terminalAdapterKind'
  private static final String AI_TERMINAL_PATH_KEY = 'ai.launcher.terminalPath'
```

Add new methods at the end of the class, before the closing brace:

```groovy
  String getAiBinaryPath(AiClient client) {
    preferences.get(AI_BINARY_KEY_PREFIX + client.name().toLowerCase(Locale.ROOT), null)
  }

  void setAiBinaryPath(AiClient client, String path) {
    String key = AI_BINARY_KEY_PREFIX + client.name().toLowerCase(Locale.ROOT)
    if (path?.trim()) {
      preferences.put(key, path)
    } else {
      preferences.remove(key)
    }
  }

  TerminalAdapterKind getTerminalAdapterKind() {
    String name = preferences.get(AI_TERMINAL_ADAPTER_KIND_KEY, null)
    name ? TerminalAdapterKind.valueOf(name) : null
  }

  void setTerminalAdapterKind(TerminalAdapterKind kind) {
    if (kind == null) {
      preferences.remove(AI_TERMINAL_ADAPTER_KIND_KEY)
    } else {
      preferences.put(AI_TERMINAL_ADAPTER_KIND_KEY, kind.name())
    }
  }

  String getTerminalPath() {
    preferences.get(AI_TERMINAL_PATH_KEY, null)
  }

  void setTerminalPath(String path) {
    if (path?.trim()) {
      preferences.put(AI_TERMINAL_PATH_KEY, path)
    } else {
      preferences.remove(AI_TERMINAL_PATH_KEY)
    }
  }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "se.alipsa.accounting.service.UserPreferencesServiceTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/service/UserPreferencesService.groovy \
        app/src/test/groovy/unit/se/alipsa/accounting/service/UserPreferencesServiceTest.groovy
git commit -m "lägger till AI-launcher-inställningar i UserPreferencesService"
```

---

## Task 16: i18n keys

**Files:**
- Modify: `app/src/main/resources/i18n/messages.properties`
- Modify: `app/src/main/resources/i18n/messages_sv.properties`

**Interfaces:**
- Produces: the i18n keys `ui/AiAssistantLauncherSection.groovy` (Task 17) reads via `I18n.instance.getString(...)`/`format(...)`. `aiClient.CLAUDE`/`CODEX`/`KIMI`/`VIBE` are the bare display names — no "(experimental)" text baked in. `aiLauncher.experimentalSuffix` holds that suffix (with its leading space escaped so `.properties` loading doesn't trim it) as a separate key, so Task 17's `displayName(AiClient)` helper can append it only when `client.experimental` is true — this is what makes flipping the `experimental` flag on `AiClient` (Task 1) actually change what the UI shows, rather than requiring someone to also remember to hand-edit these strings.

- [ ] **Step 1: Add English keys**

In `app/src/main/resources/i18n/messages.properties`, right after the existing `settings.mcp.status.unavailable=...` line (~line 871), add:

```properties
aiClient.CLAUDE=Claude Code
aiClient.CODEX=Codex
aiClient.KIMI=Kimi
aiClient.VIBE=Vibe
aiLauncher.experimentalSuffix=\ (experimental)
aiLauncher.section.title=Launch AI Assistant
aiLauncher.label.client=Client:
aiLauncher.label.binaryPath={0} binary:
aiLauncher.label.terminalAdapter=Terminal:
aiLauncher.button.detect=Detect
aiLauncher.button.launch=Launch AI Assistant
aiLauncher.error.title=AI assistant launch failed
aiLauncher.error.mcpNotRunning=The local MCP server is not running, so the assistant would be unable to connect. Start Alipsa Accounting normally and try again.
aiLauncher.error.binaryMissing=Could not find a binary for {0}. Fill in or detect the path first.
aiLauncher.error.terminalAdapterMissing=Pick a terminal and its path before launching \u2014 a path alone is not enough.
aiLauncher.error.binaryNotExecutable={0}''s configured path ({1}) is not an executable file. Fix or re-detect the path first.
aiLauncher.error.terminalNotExecutable=The configured terminal path ({0}) is not an executable file. Fix or re-detect the path first.
aiLauncher.error.launchFailed=Could not launch the AI assistant: {0}
aiLauncher.error.detectionFailed=Could not check for AI CLI/terminal installations: {0}
```

- [ ] **Step 2: Add Swedish keys**

In `app/src/main/resources/i18n/messages_sv.properties`, at the same location, add:

```properties
aiClient.CLAUDE=Claude Code
aiClient.CODEX=Codex
aiClient.KIMI=Kimi
aiClient.VIBE=Vibe
aiLauncher.experimentalSuffix=\ (experimentell)
aiLauncher.section.title=Starta AI-assistent
aiLauncher.label.client=Klient:
aiLauncher.label.binaryPath={0}-binär:
aiLauncher.label.terminalAdapter=Terminal:
aiLauncher.button.detect=Hitta
aiLauncher.button.launch=Starta AI-assistent
aiLauncher.error.title=Det gick inte att starta AI-assistenten
aiLauncher.error.mcpNotRunning=Den lokala MCP-servern körs inte, så assistenten skulle inte kunna ansluta. Starta Alipsa Bokf\u00f6ring normalt och f\u00f6rs\u00f6k igen.
aiLauncher.error.binaryMissing=Hittade ingen bin\u00e4r f\u00f6r {0}. Fyll i eller hitta s\u00f6kv\u00e4gen f\u00f6rst.
aiLauncher.error.terminalAdapterMissing=V\u00e4lj en terminal och dess s\u00f6kv\u00e4g innan du startar \u2014 enbart en s\u00f6kv\u00e4g r\u00e4cker inte.
aiLauncher.error.binaryNotExecutable=Den angivna s\u00f6kv\u00e4gen f\u00f6r {0} ({1}) \u00e4r inte en k\u00f6rbar fil. \u00c5tg\u00e4rda eller hitta s\u00f6kv\u00e4gen p\u00e5 nytt.
aiLauncher.error.terminalNotExecutable=Den angivna terminals\u00f6kv\u00e4gen ({0}) \u00e4r inte en k\u00f6rbar fil. \u00c5tg\u00e4rda eller hitta s\u00f6kv\u00e4gen p\u00e5 nytt.
aiLauncher.error.launchFailed=Det gick inte att starta AI-assistenten: {0}
aiLauncher.error.detectionFailed=Det gick inte att s\u00f6ka efter AI-CLI/terminalinstallationer: {0}
```

- [ ] **Step 3: Verify the properties files still parse**

Run: `./gradlew compileGroovy -q`
Expected: no errors (Java `.properties` loading isn't validated at compile time, but this at least confirms nothing else broke; Task 17's test is the real functional check for these keys).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/resources/i18n/messages.properties app/src/main/resources/i18n/messages_sv.properties
git commit -m "lägger till i18n-nycklar för AI-assistent-launcher"
```

---

## Task 17: `BackgroundTaskRunner` + `AiAssistantLauncherSection` (new Settings UI section)

**Files:**
- Create: `app/src/main/groovy/se/alipsa/accounting/ui/BackgroundTaskRunner.groovy`
- Create: `app/src/main/groovy/se/alipsa/accounting/ui/SwingBackgroundTaskRunner.groovy`
- Create: `app/src/main/groovy/se/alipsa/accounting/ui/AiAssistantLauncherSection.groovy`
- Test: `app/src/test/groovy/unit/se/alipsa/accounting/ui/SwingBackgroundTaskRunnerTest.groovy`
- Test: `app/src/test/groovy/unit/se/alipsa/accounting/ui/AiAssistantLauncherSectionTest.groovy`

**Interfaces:**
- Consumes: `AiClient`/`TerminalAdapterKind` (Tasks 1-2), `UserPreferencesService` (Task 15), `AiWorkspaceService` (Task 13), `AiAssistantLauncher` (Task 14), `LoopbackMcpServer.ENDPOINT` (existing).
- Produces: `interface BackgroundTaskRunner { void run(Closure backgroundWork, Closure onDone, Closure onError) }` — runs `backgroundWork` off the EDT and hops back to the EDT (via `SwingUtilities.invokeLater`) to call either `onDone` (success, with the return value) or `onError` (with whatever `backgroundWork` threw). `onError` is mandatory, not optional: without it, an exception thrown off the EDT dies silently on that background thread — the JVM's default uncaught-exception handling — and the user sees nothing happen. Real `SwingBackgroundTaskRunner` implementation. `class AiAssistantLauncherSection` with constructor `(UserPreferencesService, AiWorkspaceService, AiAssistantLauncher)` (delegates to a real `SwingBackgroundTaskRunner`) and a 4-arg `(UserPreferencesService, AiWorkspaceService, AiAssistantLauncher, BackgroundTaskRunner)` constructor for tests, `JPanel getPanel()`, `void setMcpAvailable(boolean)`, `void applyLocale()`. `launchButton` is given a stable `.name` (`'aiLauncher.launchButton'`) specifically so tests can find it unambiguously — matching "the first `JButton` with a listener and non-null text" is not unique once the per-client Detect buttons exist (they're added to the panel first and satisfy the same predicate), and matches the wrong button. The full launch workflow — executable re-validation, `refreshClientFiles`, and `AiAssistantLauncher.launch` (which itself writes files and spawns a process) — now also runs via `backgroundTaskRunner.run(...)`, not just detection/purge, since all of that is blocking I/O that must not run on the EDT. The dispatched work is `doLaunch(...)`, `@PackageScope` (not `private`) so `AiAssistantLauncherSectionTest` (same package) can call it directly with an explicit `TerminalAdapterKind` the real `terminalKindCombo` cannot offer on a non-Windows test runner (it only ever populates `TerminalAdapterKind.forCurrentOs()`). `doLaunch()` calls `aiAssistantLauncher.validatePreflight(adapterKind)` (Task 14) *first*, before `refreshClientFiles(...)` — `launch()`'s own Windows-path check only protects the wrapper script it writes, which is too late to protect the client config file `refreshClientFiles` writes first (directly embeds the plaintext token for Claude/Kimi/Vibe). Each binary `JTextField` also gets a stable `.name` (`"aiLauncher.binaryField.${client.name()}"`), for the same reason `launchButton` does — so a test can locate a specific client's field unambiguously. `autoDetectBlankFields()`'s `onDone` callback re-checks each field is *still* blank immediately before writing into it, not just at snapshot time before the scan started: since this runs automatically and asynchronously the instant the Settings panel is built, a user who starts typing a custom path before the scan completes must not have it silently overwritten and persisted once the (now-stale) result arrives — the per-client "Detect" buttons don't need this same re-check, since the user explicitly asked for that field to be overwritten by clicking. `McpServerLifecycle` (Task 19) and `MainFrame` (Task 20) depend on the 3-arg constructor and these method names — they never need to know `BackgroundTaskRunner` exists. `McpSettingsSection` (Task 18) reuses `BackgroundTaskRunner`/`SwingBackgroundTaskRunner` created here for its own purge call, with the same `onError` handling, rather than defining a second copy.

- [ ] **Step 1: Write the failing tests**

```groovy
package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.Test

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import javax.swing.SwingUtilities

class SwingBackgroundTaskRunnerTest {

  @Test
  void runsBackgroundWorkOffThreadAndAppliesTheResultOnTheEdt() {
    SwingBackgroundTaskRunner runner = new SwingBackgroundTaskRunner()
    Thread callingThread = Thread.currentThread()
    CountDownLatch latch = new CountDownLatch(1)
    List<Boolean> ranOffCallingThread = []
    List<Boolean> ranOnEdt = []
    List<String> results = []

    runner.run(
        {
          ranOffCallingThread << (Thread.currentThread() != callingThread)
          'computed-value'
        },
        { String result ->
          ranOnEdt << SwingUtilities.isEventDispatchThread()
          results << result
          latch.countDown()
        },
        { Exception exception -> throw new AssertionError('onError should not be called', exception) }
    )

    assertTrue(latch.await(5, TimeUnit.SECONDS))
    assertEquals([true], ranOffCallingThread)
    assertEquals([true], ranOnEdt)
    assertEquals(['computed-value'], results)
  }

  @Test
  void surfacesABackgroundWorkExceptionToOnErrorOnTheEdtInsteadOfLosingItSilently() {
    SwingBackgroundTaskRunner runner = new SwingBackgroundTaskRunner()
    CountDownLatch latch = new CountDownLatch(1)
    List<Boolean> ranOnEdt = []
    List<Exception> errors = []

    runner.run(
        { throw new IllegalStateException('simulated background failure') },
        { Object result -> throw new AssertionError('onDone should not be called when backgroundWork throws') },
        { Exception exception ->
          ranOnEdt << SwingUtilities.isEventDispatchThread()
          errors << exception
          latch.countDown()
        }
    )

    assertTrue(latch.await(5, TimeUnit.SECONDS))
    assertEquals([true], ranOnEdt)
    assertEquals(1, errors.size())
    assertEquals('simulated background failure', errors[0].message)
  }
}
```

```groovy
package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.AiClient
import se.alipsa.accounting.domain.TerminalAdapterKind
import se.alipsa.accounting.service.AiAssistantLauncher
import se.alipsa.accounting.service.AiWorkspacePermissions
import se.alipsa.accounting.service.AiWorkspaceService
import se.alipsa.accounting.service.AtomicSecretFileWriter
import se.alipsa.accounting.service.EnvironmentLookup
import se.alipsa.accounting.service.ExecutableProbe
import se.alipsa.accounting.service.FileDeleter
import se.alipsa.accounting.service.UserPreferencesService
import se.alipsa.accounting.support.AppPaths

import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JTextField

import java.nio.file.Files
import java.nio.file.Path
import java.util.prefs.Preferences

class AiAssistantLauncherSectionTest {

  @TempDir
  Path tempDir

  // Runs backgroundWork, onDone, and onError synchronously on the calling (test) thread, so
  // detection results are deterministic instead of racing a real background thread + invokeLater
  // — every test here uses this instead of the real SwingBackgroundTaskRunner.
  private static final BackgroundTaskRunner SYNCHRONOUS_RUNNER =
      { Closure backgroundWork, Closure onDone, Closure onError ->
        try {
          onDone.call(backgroundWork.call())
        } catch (Exception exception) {
          onError.call(exception)
        }
      } as BackgroundTaskRunner

  @Test
  void launchButtonStartsDisabledAndIsEnabledOnceMcpIsAvailable() {
    Preferences node = Preferences.userRoot().node("alipsa-accounting-test-${UUID.randomUUID()}")
    try {
      UserPreferencesService userPreferencesService = new UserPreferencesService(node)
      AiWorkspaceService aiWorkspaceService = new AiWorkspaceService()
      AiAssistantLauncher aiAssistantLauncher = new AiAssistantLauncher()
      AiAssistantLauncherSection section = new AiAssistantLauncherSection(
          userPreferencesService, aiWorkspaceService, aiAssistantLauncher, SYNCHRONOUS_RUNNER)

      JButton launchButton = findLaunchButton(section.panel)
      assertFalse(launchButton.enabled)

      section.setMcpAvailable(true)

      assertTrue(launchButton.enabled)
    } finally {
      node.removeNode()
    }
  }

  @Test
  void clientDropdownOffersAllFourClients() {
    Preferences node = Preferences.userRoot().node("alipsa-accounting-test-${UUID.randomUUID()}")
    try {
      UserPreferencesService userPreferencesService = new UserPreferencesService(node)
      AiAssistantLauncherSection section = new AiAssistantLauncherSection(
          userPreferencesService, new AiWorkspaceService(), new AiAssistantLauncher(), SYNCHRONOUS_RUNNER)

      JComboBox<AiClient> combo = findClientCombo(section.panel)

      assert combo.itemCount == 4
    } finally {
      node.removeNode()
    }
  }

  @Test
  void doLaunchNeverWritesClaudesConfigWhenTheWindowsWorkspacePathIsUnsafe() {
    // terminalKindCombo only ever offers TerminalAdapterKind.forCurrentOs() (Linux/macOS CI never
    // offers WINDOWS_TERMINAL), so this can't be driven through the real combo box on this host —
    // it calls doLaunch() directly instead: the exact @PackageScope production method onLaunch()
    // dispatches to, just without needing to first select an option the UI structurally cannot
    // offer here. Proves the fix for the specific gap a review found: the Windows-path preflight
    // used to run only inside AiAssistantLauncher.launch(), which is called AFTER
    // refreshClientFiles() already wrote Claude's token-bearing .mcp.json — so a refusal used to
    // still leave that secret-bearing file behind. doLaunch() now calls
    // aiAssistantLauncher.validatePreflight(adapterKind) first, before refreshClientFiles().
    Path unsafeHome = tempDir.resolve('unsafe & home')
    Files.createDirectories(unsafeHome)
    String previousOverride = System.getProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY)
    System.setProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY, unsafeHome.toString())
    Preferences node = Preferences.userRoot().node("alipsa-accounting-test-${UUID.randomUUID()}")
    try {
      UserPreferencesService userPreferencesService = new UserPreferencesService(node)
      AiAssistantLauncherSection section = new AiAssistantLauncherSection(
          userPreferencesService, new AiWorkspaceService(), new AiAssistantLauncher(), SYNCHRONOUS_RUNNER)

      assertThrows(IllegalArgumentException) {
        section.doLaunch(AiClient.CLAUDE, '/usr/local/bin/claude', TerminalAdapterKind.WINDOWS_TERMINAL, 'C:/Windows/System32/wt.exe', 'test-token')
      }

      assertFalse(Files.exists(AppPaths.aiWorkspaceDirectory().resolve('.mcp.json')))
    } finally {
      node.removeNode()
      if (previousOverride == null) {
        System.clearProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY)
      } else {
        System.setProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY, previousOverride)
      }
    }
  }

  @Test
  void autoDetectDoesNotOverwriteABinaryPathTheUserTypedWhileDetectionWasStillRunning() {
    Preferences node = Preferences.userRoot().node("alipsa-accounting-test-${UUID.randomUUID()}")
    try {
      UserPreferencesService userPreferencesService = new UserPreferencesService(node)
      // A fake PATH + an ExecutableProbe that accepts anything, so detectBinaryPath() finds a
      // deterministic result for every client — not dependent on what happens to be installed on
      // whatever machine runs this test.
      AiWorkspaceService aiWorkspaceService = new AiWorkspaceService(
          new AiWorkspacePermissions(),
          new AtomicSecretFileWriter(),
          { Path candidate -> true } as ExecutableProbe,
          { String name -> name == 'PATH' ? '/fake/bin' : null } as EnvironmentLookup,
          { Path path -> Files.deleteIfExists(path) } as FileDeleter)
      DelayedBackgroundTaskRunner delayedRunner = new DelayedBackgroundTaskRunner()

      AiAssistantLauncherSection section = new AiAssistantLauncherSection(
          userPreferencesService, aiWorkspaceService, new AiAssistantLauncher(), delayedRunner)
      // autoDetectBlankFields() ran during construction and computed its result already (the
      // fake runner runs backgroundWork eagerly), but hasn't applied it yet — this is the gap a
      // real background thread + invokeLater leaves open. Simulate the user typing into the
      // still-blank Codex field during that gap, before the deferred result is applied.
      JTextField codexField = findBinaryField(section.panel, AiClient.CODEX)
      codexField.text = '/my/custom/codex'

      delayedRunner.completeAll()

      // Not overwritten in the field, and never persisted either — the fix skips both the field
      // write and the userPreferencesService.setAiBinaryPath(...) call together, since they're
      // gated by the same still-blank check.
      assertEquals('/my/custom/codex', codexField.text)
    } finally {
      node.removeNode()
    }
  }

  private static JButton findLaunchButton(JPanel root) {
    // Match by the stable component name AiAssistantLauncherSection assigns to the real launch
    // button, not by "first JButton with a listener and non-null text" — the per-client Detect
    // buttons satisfy that predicate too and are added to the panel first, so it used to match
    // the wrong button entirely.
    findComponent(root, JButton) { JButton button -> button.name == 'aiLauncher.launchButton' }
  }

  private static JComboBox findClientCombo(JPanel root) {
    findComponent(root, JComboBox) { JComboBox combo -> combo.itemCount == 4 }
  }

  private static JTextField findBinaryField(JPanel root, AiClient client) {
    findComponent(root, JTextField) { JTextField field -> field.name == "aiLauncher.binaryField.${client.name()}" }
  }

  /**
   * Runs {@code backgroundWork} immediately (like {@code SYNCHRONOUS_RUNNER}) but defers calling
   * {@code onDone}/{@code onError} until the test explicitly calls {@link #completeAll}, so a
   * test can simulate the gap a real background thread + {@code SwingUtilities.invokeLater}
   * leaves open between "the scan finished" and "the result got applied to the UI."
   */
  private static final class DelayedBackgroundTaskRunner implements BackgroundTaskRunner {
    private final List<Closure> pendingCompletions = []

    @Override
    void run(Closure backgroundWork, Closure onDone, Closure onError) {
      try {
        def result = backgroundWork.call()
        pendingCompletions << { -> onDone.call(result) }
      } catch (Exception exception) {
        pendingCompletions << { -> onError.call(exception) }
      }
    }

    void completeAll() {
      List<Closure> toRun = new ArrayList<>(pendingCompletions)
      pendingCompletions.clear()
      toRun.each { it.call() }
    }
  }

  private static <T> T findComponent(java.awt.Container container, Class<T> type, Closure<Boolean> predicate) {
    for (java.awt.Component component : container.components) {
      if (type.isInstance(component) && predicate.call(component)) {
        return type.cast(component)
      }
      if (component instanceof java.awt.Container) {
        T found = findComponent(component as java.awt.Container, type, predicate)
        if (found != null) {
          return found
        }
      }
    }
    null
  }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "se.alipsa.accounting.ui.SwingBackgroundTaskRunnerTest" --tests "se.alipsa.accounting.ui.AiAssistantLauncherSectionTest"`
Expected: FAIL — none of the new classes exist yet.

- [ ] **Step 3: Write the implementation**

```groovy
// app/src/main/groovy/se/alipsa/accounting/ui/BackgroundTaskRunner.groovy
package se.alipsa.accounting.ui

/**
 * Seam around "do work off the EDT, then hop back to the EDT to apply the
 * result or report the failure" — lets filesystem/process I/O (binary/
 * terminal detection, workspace secret purge, and the full launch workflow)
 * run without blocking Swing's event dispatch thread, while staying
 * synchronous and deterministic when tests supply a same-thread fake instead
 * of the real implementation. `onError` is mandatory: a `backgroundWork`
 * that throws must never die silently on its background thread with the
 * user seeing nothing.
 */
interface BackgroundTaskRunner {
  void run(Closure backgroundWork, Closure onDone, Closure onError)
}
```

```groovy
// app/src/main/groovy/se/alipsa/accounting/ui/SwingBackgroundTaskRunner.groovy
package se.alipsa.accounting.ui

import java.util.logging.Level
import java.util.logging.Logger

import javax.swing.SwingUtilities

/** Real implementation: runs {@code backgroundWork} on a new daemon thread, then posts either {@code onDone} (success) or {@code onError} (any exception {@code backgroundWork} threw) to the EDT via {@code SwingUtilities.invokeLater}. */
final class SwingBackgroundTaskRunner implements BackgroundTaskRunner {

  private static final Logger log = Logger.getLogger(SwingBackgroundTaskRunner.name)

  @Override
  void run(Closure backgroundWork, Closure onDone, Closure onError) {
    Thread thread = new Thread({
      try {
        def result = backgroundWork.call()
        SwingUtilities.invokeLater { onDone.call(result) }
      } catch (Exception exception) {
        log.log(Level.WARNING, 'Background task failed', exception)
        SwingUtilities.invokeLater { onError.call(exception) }
      }
    } as Runnable, 'ai-workspace-background')
    thread.daemon = true
    thread.start()
  }
}
```

```groovy
// app/src/main/groovy/se/alipsa/accounting/ui/AiAssistantLauncherSection.groovy
package se.alipsa.accounting.ui

import groovy.transform.PackageScope

import se.alipsa.accounting.domain.AiClient
import se.alipsa.accounting.domain.TerminalAdapterKind
import se.alipsa.accounting.mcp.LoopbackMcpServer
import se.alipsa.accounting.service.AiAssistantLauncher
import se.alipsa.accounting.service.AiWorkspaceService
import se.alipsa.accounting.service.UserPreferencesService
import se.alipsa.accounting.support.I18n

import java.awt.FlowLayout
import java.nio.file.Path
import java.nio.file.Paths

import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.border.TitledBorder

/** Settings UI for launching an AI CLI wired to the local MCP endpoint. */
final class AiAssistantLauncherSection {

  private static final String LAUNCH_BUTTON_NAME = 'aiLauncher.launchButton'

  private final UserPreferencesService userPreferencesService
  private final AiWorkspaceService aiWorkspaceService
  private final AiAssistantLauncher aiAssistantLauncher
  private final BackgroundTaskRunner backgroundTaskRunner
  private final JPanel panel = new JPanel()
  private final TitledBorder border = BorderFactory.createTitledBorder('')
  private final Map<AiClient, JLabel> binaryLabels = [:]
  private final Map<AiClient, JTextField> binaryFields = [:]
  private final Map<AiClient, JButton> detectBinaryButtons = [:]
  private final JLabel terminalKindLabel = new JLabel()
  private final JComboBox<TerminalAdapterKind> terminalKindCombo =
      new JComboBox<>(TerminalAdapterKind.forCurrentOs() as TerminalAdapterKind[])
  private final JTextField terminalPathField = new JTextField(24)
  private final JButton detectTerminalButton = new JButton()
  private final JLabel clientLabel = new JLabel()
  private final JComboBox<AiClient> clientCombo = new JComboBox<>(AiClient.values())
  private final JButton launchButton = new JButton()
  private boolean mcpAvailable = false

  AiAssistantLauncherSection(
      UserPreferencesService userPreferencesService,
      AiWorkspaceService aiWorkspaceService,
      AiAssistantLauncher aiAssistantLauncher
  ) {
    this(userPreferencesService, aiWorkspaceService, aiAssistantLauncher, new SwingBackgroundTaskRunner())
  }

  AiAssistantLauncherSection(
      UserPreferencesService userPreferencesService,
      AiWorkspaceService aiWorkspaceService,
      AiAssistantLauncher aiAssistantLauncher,
      BackgroundTaskRunner backgroundTaskRunner
  ) {
    this.userPreferencesService = userPreferencesService
    this.aiWorkspaceService = aiWorkspaceService
    this.aiAssistantLauncher = aiAssistantLauncher
    this.backgroundTaskRunner = backgroundTaskRunner
    panel.layout = new BoxLayout(panel, BoxLayout.Y_AXIS)
    panel.border = border
    launchButton.name = LAUNCH_BUTTON_NAME
    buildRows()
    autoDetectBlankFields()
    applyLocale()
    updateLaunchButtonState()
  }

  JPanel getPanel() {
    panel
  }

  void setMcpAvailable(boolean available) {
    mcpAvailable = available
    updateLaunchButtonState()
  }

  void applyLocale() {
    border.title = I18n.instance.getString('aiLauncher.section.title')
    AiClient.values().each { AiClient client ->
      binaryLabels[client].text = I18n.instance.format('aiLauncher.label.binaryPath', displayName(client))
      detectBinaryButtons[client].text = I18n.instance.getString('aiLauncher.button.detect')
    }
    terminalKindLabel.text = I18n.instance.getString('aiLauncher.label.terminalAdapter')
    detectTerminalButton.text = I18n.instance.getString('aiLauncher.button.detect')
    clientLabel.text = I18n.instance.getString('aiLauncher.label.client')
    launchButton.text = I18n.instance.getString('aiLauncher.button.launch')
  }

  /**
   * The client's display name, with the "(experimental)" suffix appended only when
   * {@link AiClient#experimental} is true — so promoting a client out of experimental status
   * (Task 22's manual-verification follow-up) is a one-line flag flip in {@code AiClient}, not a
   * flag flip plus a separate, easy-to-forget edit to two i18n `.properties` files.
   */
  private static String displayName(AiClient client) {
    String base = I18n.instance.getString("aiClient.${client.name()}")
    client.experimental ? base + I18n.instance.getString('aiLauncher.experimentalSuffix') : base
  }

  private void buildRows() {
    AiClient.values().each { AiClient client ->
      JLabel label = new JLabel()
      binaryLabels[client] = label
      JTextField field = new JTextField(userPreferencesService.getAiBinaryPath(client) ?: '', 24)
      field.name = "aiLauncher.binaryField.${client.name()}"
      binaryFields[client] = field
      JButton detect = new JButton()
      detect.addActionListener {
        backgroundTaskRunner.run(
            { aiWorkspaceService.detectBinaryPath(client) },
            { Path resolved ->
              if (resolved != null) {
                field.text = resolved.toString()
                userPreferencesService.setAiBinaryPath(client, resolved.toString())
              }
            },
            this.&showDetectionError
        )
      }
      detectBinaryButtons[client] = detect
      JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0))
      row.add(label)
      row.add(field)
      row.add(detect)
      panel.add(row)
    }

    TerminalAdapterKind storedKind = userPreferencesService.terminalAdapterKind
    if (storedKind != null && TerminalAdapterKind.forCurrentOs().contains(storedKind)) {
      terminalKindCombo.selectedItem = storedKind
    }
    terminalPathField.text = userPreferencesService.terminalPath ?: ''
    detectTerminalButton.addActionListener {
      backgroundTaskRunner.run(
          { aiWorkspaceService.detectTerminalAdapter() },
          { Tuple2<TerminalAdapterKind, Path> detected ->
            if (detected != null) {
              terminalKindCombo.selectedItem = detected.v1
              terminalPathField.text = detected.v2.toString()
              userPreferencesService.terminalAdapterKind = detected.v1
              userPreferencesService.terminalPath = detected.v2.toString()
            }
          },
          this.&showDetectionError
      )
    }
    JPanel terminalRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0))
    terminalRow.add(terminalKindLabel)
    terminalRow.add(terminalKindCombo)
    terminalRow.add(terminalPathField)
    terminalRow.add(detectTerminalButton)
    panel.add(terminalRow)

    launchButton.addActionListener { onLaunch() }
    JPanel launchRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0))
    launchRow.add(clientLabel)
    launchRow.add(clientCombo)
    launchRow.add(launchButton)
    panel.add(launchRow)
  }

  /**
   * Snapshots which fields are blank on the EDT (cheap, no I/O — reading Swing component state
   * off the EDT isn't safe), then does the actual PATH scanning in the background closure, which
   * touches no Swing component. The scan can take a moment (multiple PATH directories, each a
   * stat() call), and this runs automatically the instant the Settings panel is built — so
   * {@code onDone}, back on the EDT, re-checks each field is *still* blank right before writing
   * into it, not just at snapshot time above. Without that re-check, a user who starts typing a
   * custom path into a field the instant the panel appears — before this scan completes — could
   * have their input silently overwritten and persisted to preferences once the result arrives.
   * The per-client "Detect" buttons (in {@link #buildRows}) don't need this: the user explicitly
   * asked for detection on that specific field by clicking, so overwriting it is what was asked for.
   */
  private void autoDetectBlankFields() {
    List<AiClient> blankClients = AiClient.values().findAll { AiClient client -> !binaryFields[client].text?.trim() }
    boolean terminalBlank = !terminalPathField.text?.trim()
    backgroundTaskRunner.run(
        {
          Map<AiClient, Path> resolvedBinaries = [:]
          blankClients.each { AiClient client ->
            Path resolved = aiWorkspaceService.detectBinaryPath(client)
            if (resolved != null) {
              resolvedBinaries[client] = resolved
            }
          }
          Tuple2<TerminalAdapterKind, Path> resolvedTerminal = terminalBlank ? aiWorkspaceService.detectTerminalAdapter() : null
          new Tuple2<Map<AiClient, Path>, Tuple2<TerminalAdapterKind, Path>>(resolvedBinaries, resolvedTerminal)
        },
        { Tuple2<Map<AiClient, Path>, Tuple2<TerminalAdapterKind, Path>> result ->
          result.v1.each { AiClient client, Path resolved ->
            if (!binaryFields[client].text?.trim()) {
              binaryFields[client].text = resolved.toString()
              userPreferencesService.setAiBinaryPath(client, resolved.toString())
            }
          }
          Tuple2<TerminalAdapterKind, Path> terminal = result.v2
          if (terminal != null && !terminalPathField.text?.trim()) {
            terminalKindCombo.selectedItem = terminal.v1
            terminalPathField.text = terminal.v2.toString()
            userPreferencesService.terminalAdapterKind = terminal.v1
            userPreferencesService.terminalPath = terminal.v2.toString()
          }
        },
        this.&showDetectionError
    )
  }

  private void updateLaunchButtonState() {
    launchButton.enabled = mcpAvailable
    launchButton.toolTipText = mcpAvailable ? null : I18n.instance.getString('aiLauncher.error.mcpNotRunning')
  }

  private void onLaunch() {
    if (!mcpAvailable) {
      showError(I18n.instance.getString('aiLauncher.error.mcpNotRunning'))
      return
    }
    AiClient client = clientCombo.selectedItem as AiClient
    String binaryPathText = binaryFields[client].text?.trim()
    TerminalAdapterKind adapterKind = terminalKindCombo.selectedItem as TerminalAdapterKind
    String terminalPathText = terminalPathField.text?.trim()
    if (!binaryPathText) {
      showError(I18n.instance.format('aiLauncher.error.binaryMissing', displayName(client)))
      return
    }
    if (adapterKind == null || !terminalPathText) {
      showError(I18n.instance.getString('aiLauncher.error.terminalAdapterMissing'))
      return
    }
    String token = userPreferencesService.ensureMcpToken()
    launchButton.enabled = false
    backgroundTaskRunner.run(
        { doLaunch(client, binaryPathText, adapterKind, terminalPathText, token) },
        { String validationErrorMessage ->
          updateLaunchButtonState()
          if (validationErrorMessage != null) {
            showError(validationErrorMessage)
          }
        },
        { Exception exception ->
          updateLaunchButtonState()
          showError(I18n.instance.format('aiLauncher.error.launchFailed', exception.message ?: exception.class.simpleName))
        }
    )
  }

  /**
   * Runs entirely off the EDT: executable re-validation, config/instructions writes
   * ({@code refreshClientFiles}), and the terminal spawn ({@code AiAssistantLauncher.launch}) are
   * all filesystem/process I/O and must not block Settings while they run. Returns null on
   * success, or a ready-to-show i18n error message for the two validation outcomes this method
   * itself detects. Any exception `validatePreflight`/`refreshClientFiles`/`launch` throw is
   * deliberately left to propagate — `onLaunch()`'s `onError` callback handles it, so it isn't
   * caught twice. `validatePreflight` runs *first*, before `refreshClientFiles`: `launch()`'s own
   * Windows-path safety check (Task 14) only protects the wrapper script it writes, which is too
   * late for `refreshClientFiles`'s client config file — for Claude/Kimi/Vibe, that file directly
   * embeds the plaintext MCP token, so a refusal must happen before it's written, not after.
   * `@PackageScope`, not `private`, specifically so `AiAssistantLauncherSectionTest` can call it
   * directly with an explicit `TerminalAdapterKind.WINDOWS_TERMINAL` — the real `terminalKindCombo`
   * only ever offers `TerminalAdapterKind.forCurrentOs()`, so a Linux/macOS test runner can never
   * select that option through the actual UI combo box.
   */
  @PackageScope
  String doLaunch(AiClient client, String binaryPathText, TerminalAdapterKind adapterKind, String terminalPathText, String token) {
    aiAssistantLauncher.validatePreflight(adapterKind)
    Path binaryPathCandidate = Paths.get(binaryPathText)
    if (!aiWorkspaceService.isValidExecutable(binaryPathCandidate)) {
      return I18n.instance.format('aiLauncher.error.binaryNotExecutable', displayName(client), binaryPathText)
    }
    Path terminalPathCandidate = Paths.get(terminalPathText)
    if (!aiWorkspaceService.isValidExecutable(terminalPathCandidate)) {
      return I18n.instance.format('aiLauncher.error.terminalNotExecutable', terminalPathText)
    }
    userPreferencesService.setAiBinaryPath(client, binaryPathText)
    userPreferencesService.terminalAdapterKind = adapterKind
    userPreferencesService.terminalPath = terminalPathText
    aiWorkspaceService.refreshClientFiles(client, LoopbackMcpServer.ENDPOINT, token)
    aiAssistantLauncher.launch(client, binaryPathCandidate, adapterKind, terminalPathCandidate, token)
    null
  }

  private void showDetectionError(Exception exception) {
    showError(I18n.instance.format('aiLauncher.error.detectionFailed', exception.message ?: exception.class.simpleName))
  }

  private void showError(String message) {
    JOptionPane.showMessageDialog(panel, message, I18n.instance.getString('aiLauncher.error.title'), JOptionPane.ERROR_MESSAGE)
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "se.alipsa.accounting.ui.SwingBackgroundTaskRunnerTest" --tests "se.alipsa.accounting.ui.AiAssistantLauncherSectionTest"`
Expected: PASS (2 tests + 4 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/ui/BackgroundTaskRunner.groovy \
        app/src/main/groovy/se/alipsa/accounting/ui/SwingBackgroundTaskRunner.groovy \
        app/src/main/groovy/se/alipsa/accounting/ui/AiAssistantLauncherSection.groovy \
        app/src/test/groovy/unit/se/alipsa/accounting/ui/SwingBackgroundTaskRunnerTest.groovy \
        app/src/test/groovy/unit/se/alipsa/accounting/ui/AiAssistantLauncherSectionTest.groovy
git commit -m "lägger till BackgroundTaskRunner och AiAssistantLauncherSection"
```

---

## Task 18: `McpSettingsSection` — inject `AiWorkspaceService`, reorder token rotation

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/ui/McpSettingsSection.groovy`
- Create: `app/src/test/groovy/unit/se/alipsa/accounting/ui/McpSettingsSectionTest.groovy`

**Interfaces:**
- Consumes: `AiWorkspaceService`/`PurgeResult` (Task 13), `BackgroundTaskRunner`/`SwingBackgroundTaskRunner` (Task 17 — same `se.alipsa.accounting.ui` package, no new files needed here).
- Produces: `McpSettingsSection(UserPreferencesService, AiWorkspaceService)` (new 2-arg constructor — the 1-arg one used by `MainFrame` today is removed; internally delegates to a real `SwingBackgroundTaskRunner`) plus a 3-arg `(UserPreferencesService, AiWorkspaceService, BackgroundTaskRunner)` constructor for tests. `McpServerLifecycle`/`MainFrame` (Tasks 19-20) keep using the 2-arg constructor and never need to know `BackgroundTaskRunner` exists. `regenerateToken()`'s purge now runs off the EDT via `backgroundTaskRunner.run(backgroundWork, onDone, onError)` (Task 17's 3-arg form), with the token rotation and the "some files couldn't be removed" dialog applied back on the EDT in `onDone`, and an unexpected exception from `purgeAllSecrets()` itself — not the normal "some files failed" `PurgeResult`, but a genuine thrown exception — surfaced via `onError` instead of dying silently on the background thread.

- [ ] **Step 1: Write the failing test** (new file — no test existed before for this class)

```groovy
package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotEquals
import static org.junit.jupiter.api.Assumptions.assumeTrue

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.AiClient
import se.alipsa.accounting.service.AiWorkspaceService
import se.alipsa.accounting.service.UserPreferencesService
import se.alipsa.accounting.support.AppPaths

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.prefs.Preferences

class McpSettingsSectionTest {

  @TempDir
  Path tempDir

  // Runs regenerateToken()'s purge synchronously on the test thread, so the assertions right
  // after calling it are valid — same pattern as AiAssistantLauncherSectionTest (Task 17).
  private static final BackgroundTaskRunner SYNCHRONOUS_RUNNER =
      { Closure backgroundWork, Closure onDone, Closure onError ->
        try {
          onDone.call(backgroundWork.call())
        } catch (Exception exception) {
          onError.call(exception)
        }
      } as BackgroundTaskRunner

  @Test
  void regenerateTokenPurgesWorkspaceSecretsBeforeRotating() {
    // Never let this test (or any test) touch the real user's AI workspace — always redirect
    // via the dedicated test-only override, exactly like AiWorkspaceServiceTest/AiAssistantLauncherTest.
    assumeTrue(FileSystems.default.supportedFileAttributeViews().contains('posix'))
    String previousOverride = System.getProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY)
    System.setProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY, tempDir.toString())
    Preferences node = Preferences.userRoot().node("alipsa-accounting-test-${UUID.randomUUID()}")
    try {
      UserPreferencesService userPreferencesService = new UserPreferencesService(node)
      String originalToken = userPreferencesService.ensureMcpToken()
      AiWorkspaceService aiWorkspaceService = new AiWorkspaceService()
      aiWorkspaceService.refreshClientFiles(AiClient.CLAUDE, 'http://127.0.0.1:48652/mcp', originalToken)

      McpSettingsSection section = new McpSettingsSection(userPreferencesService, aiWorkspaceService, SYNCHRONOUS_RUNNER)
      section.regenerateToken()

      assertNotEquals(originalToken, userPreferencesService.ensureMcpToken())
      assertEquals(false, Files.exists(AppPaths.aiWorkspaceDirectory().resolve('.mcp.json')))
    } finally {
      node.removeNode()
      if (previousOverride == null) {
        System.clearProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY)
      } else {
        System.setProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY, previousOverride)
      }
    }
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "se.alipsa.accounting.ui.McpSettingsSectionTest"`
Expected: FAIL — 3-arg constructor and `regenerateToken()` don't exist yet.

- [ ] **Step 3: Modify the implementation**

In `McpSettingsSection.groovy`:
1. Add `import se.alipsa.accounting.service.AiWorkspaceService`. (`BackgroundTaskRunner`/`SwingBackgroundTaskRunner` need no import — same `se.alipsa.accounting.ui` package.)
2. Add fields `private final AiWorkspaceService aiWorkspaceService` and `private final BackgroundTaskRunner backgroundTaskRunner`.
3. Change the constructors:

```groovy
  McpSettingsSection(UserPreferencesService userPreferencesService, AiWorkspaceService aiWorkspaceService) {
    this(userPreferencesService, aiWorkspaceService, new SwingBackgroundTaskRunner())
  }

  McpSettingsSection(
      UserPreferencesService userPreferencesService,
      AiWorkspaceService aiWorkspaceService,
      BackgroundTaskRunner backgroundTaskRunner
  ) {
    this.userPreferencesService = userPreferencesService
    this.aiWorkspaceService = aiWorkspaceService
    this.backgroundTaskRunner = backgroundTaskRunner
    panel.layout = new BoxLayout(panel, BoxLayout.Y_AXIS)
    border = BorderFactory.createTitledBorder('')
    panel.border = border
    endpointField = new JTextField(LoopbackMcpServer.ENDPOINT, 30)
    endpointField.editable = false
    tokenField = new JTextField(userPreferencesService.ensureMcpToken(), 30)
    tokenField.editable = false
    buildRows()
    applyLocale()
  }
```

4. Replace the regenerate-button wiring inside `buildRows()`:

```groovy
    regenerateButton.addActionListener { regenerateToken() }
```

5. Add a new public method (extracted so the test above can call it directly, and so the reorder logic lives in one obvious place). The purge (filesystem I/O) runs in the background closure; the failure dialog and the actual token rotation only happen in `onDone`, back on the EDT; `onError` covers the (rare) case where `purgeAllSecrets()` itself throws rather than returning a `PurgeResult` with some entries failed \u2014 without it, that exception would die silently on the background thread and the user would see no rotation happen with no explanation:

```groovy
  void regenerateToken() {
    backgroundTaskRunner.run(
        { aiWorkspaceService.purgeAllSecrets() },
        { se.alipsa.accounting.service.PurgeResult result ->
          if (!result.complete) {
            javax.swing.JOptionPane.showMessageDialog(
                panel,
                I18n.instance.format('settings.mcp.rotateFailed', result.failed.join(', ')),
                I18n.instance.getString('settings.mcp.rotateFailedTitle'),
                javax.swing.JOptionPane.ERROR_MESSAGE)
            return
          }
          tokenField.text = userPreferencesService.regenerateMcpToken()
        },
        { Exception exception ->
          javax.swing.JOptionPane.showMessageDialog(
              panel,
              I18n.instance.format('settings.mcp.rotateError', exception.message ?: exception.class.simpleName),
              I18n.instance.getString('settings.mcp.rotateFailedTitle'),
              javax.swing.JOptionPane.ERROR_MESSAGE)
        }
    )
  }
```

- [ ] **Step 4: Add the three new i18n keys this uses**

In `messages.properties`:
```properties
settings.mcp.rotateFailedTitle=Could not rotate the MCP token
settings.mcp.rotateFailed=Some AI-workspace files could not be removed and still use the old token: {0}. Fix the listed files' permissions and try again.
settings.mcp.rotateError=Purging the AI workspace failed unexpectedly, so the token was not rotated: {0}
```

In `messages_sv.properties`:
```properties
settings.mcp.rotateFailedTitle=Det gick inte att generera en ny MCP-token
settings.mcp.rotateFailed=Vissa filer i AI-arbetsytan kunde inte tas bort och anv\u00e4nder fortfarande den gamla token: {0}. \u00c5tg\u00e4rda r\u00e4ttigheterna f\u00f6r de listade filerna och f\u00f6rs\u00f6k igen.
settings.mcp.rotateError=Rensningen av AI-arbetsytan misslyckades ov\u00e4ntat, s\u00e5 token roterades inte: {0}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "se.alipsa.accounting.ui.McpSettingsSectionTest"`
Expected: PASS (1 test)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/ui/McpSettingsSection.groovy \
        app/src/main/resources/i18n/messages.properties app/src/main/resources/i18n/messages_sv.properties \
        app/src/test/groovy/unit/se/alipsa/accounting/ui/McpSettingsSectionTest.groovy
git commit -m "ordnar om token-rotation i McpSettingsSection (purge före rotation)"
```

---

## Task 19: `McpServerLifecycle` — wire availability + shutdown purge

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/ui/McpServerLifecycle.groovy`

**Interfaces:**
- Consumes: `AiWorkspaceService` (Task 13), `AiAssistantLauncherSection` (Task 17).
- Produces: `McpServerLifecycle(UserPreferencesService, ActiveCompanyManager, VoucherPanel, McpSettingsSection, JPanel, AiWorkspaceService, AiAssistantLauncherSection)` — a 7-arg constructor (was 5-arg). `MainFrame` (Task 20) must pass the two new arguments.

This task has no dedicated new test file — its two behavioral changes (`setMcpAvailable` gets called from `start()`, `purgeAllSecrets()` gets called from `close()`) are thin wiring over already-tested collaborators (`AiAssistantLauncherSection.setMcpAvailable` from Task 17, `AiWorkspaceService.purgeAllSecrets` from Task 13). It's verified by the full build compiling and by manual smoke testing in Task 22.

- [ ] **Step 1: Modify the implementation**

In `McpServerLifecycle.groovy`:
1. Add imports:
```groovy
import se.alipsa.accounting.service.AiWorkspaceService
```
2. Add two new fields:
```groovy
  private final AiWorkspaceService aiWorkspaceService
  private final AiAssistantLauncherSection aiLauncherSection
```
3. Change the constructor:
```groovy
  McpServerLifecycle(
      UserPreferencesService userPreferencesService,
      ActiveCompanyManager activeCompanyManager,
      VoucherPanel voucherPanel,
      McpSettingsSection settingsSection,
      JPanel glassPane,
      AiWorkspaceService aiWorkspaceService,
      AiAssistantLauncherSection aiLauncherSection
  ) {
    this.userPreferencesService = userPreferencesService
    this.activeCompanyManager = activeCompanyManager
    this.voucherPanel = voucherPanel
    this.settingsSection = settingsSection
    this.glassPane = glassPane
    this.aiWorkspaceService = aiWorkspaceService
    this.aiLauncherSection = aiLauncherSection
  }
```
4. In `start()`, update both branches to also inform the launcher section:
```groovy
      server = new LoopbackMcpServer(userPreferencesService, new McpDispatcher(tools), uiGuard())
      server.start()
      settingsSection.setRunning()
      aiLauncherSection.setMcpAvailable(true)
      log.info("Local MCP server available at ${LoopbackMcpServer.ENDPOINT}")
    } catch (Exception exception) {
      log.warning("Could not start local MCP server: ${exception.message}")
      settingsSection.setUnavailable(exception.message)
      aiLauncherSection.setMcpAvailable(false)
    }
```
5. Change `close()` to also purge, best-effort, without letting a purge failure block shutdown:
```groovy
  @Override
  void close() {
    server?.close()
    try {
      aiWorkspaceService.purgeAllSecrets()
    } catch (Exception exception) {
      log.warning("Could not purge AI workspace secrets on shutdown: ${exception.message}")
    }
  }
```

- [ ] **Step 2: Compile to verify the change is syntactically consistent**

Run: `./gradlew compileGroovy -q`
Expected: fails until Task 20 updates the one call site in `MainFrame.groovy` — that's fine, do Task 20 immediately after this one before running the full build.

- [ ] **Step 3: Commit** (commit together with Task 20, since `MainFrame` must change in the same commit to keep the build compiling — see Task 20's commit step, which includes this file too)

---

## Task 20: `MainFrame` wiring

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/ui/MainFrame.groovy`

**Interfaces:**
- Consumes: `AiWorkspaceService`, `AiAssistantLauncher` (Tasks 13-14), `AiAssistantLauncherSection` (Task 17), the new `McpSettingsSection`/`McpServerLifecycle` constructors (Tasks 18-19).

- [ ] **Step 1: Add the new fields and construct the new collaborators**

Add imports:
```groovy
import se.alipsa.accounting.service.AiAssistantLauncher
import se.alipsa.accounting.service.AiWorkspaceService
```

Add fields near the existing `mcpSettingsSection`/`mcpServerLifecycle` fields (~line 223-227):
```groovy
  private final AiWorkspaceService aiWorkspaceService = new AiWorkspaceService()
  private final AiAssistantLauncher aiAssistantLauncher = new AiAssistantLauncher()
  private AiAssistantLauncherSection aiLauncherSection
```

- [ ] **Step 2: Construct `aiLauncherSection` where `mcpSettingsSection` is built**

In `buildSettingsPanel()` (~line 456-478), change:
```groovy
    mcpSettingsSection = new McpSettingsSection(userPreferencesService)
    JPanel mcpSection = mcpSettingsSection.panel
```
to:
```groovy
    mcpSettingsSection = new McpSettingsSection(userPreferencesService, aiWorkspaceService)
    JPanel mcpSection = mcpSettingsSection.panel
    aiLauncherSection = new AiAssistantLauncherSection(userPreferencesService, aiWorkspaceService, aiAssistantLauncher)
    JPanel aiLauncherPanel = aiLauncherSection.panel
    aiLauncherPanel.alignmentX = Component.LEFT_ALIGNMENT
```

Then add it to the panel, right after `panel.add(mcpSection)`:
```groovy
    panel.add(mcpSection)
    panel.add(Box.createVerticalStrut(12))
    panel.add(aiLauncherPanel)
```

- [ ] **Step 3: Pass the two new arguments to `McpServerLifecycle`**

Change the constructor call (~line 238-239):
```groovy
    mcpServerLifecycle = new McpServerLifecycle(
        userPreferencesService, activeCompanyManager, voucherPanel, mcpSettingsSection, mcpGlassPane,
        aiWorkspaceService, aiLauncherSection)
```

- [ ] **Step 4: Add locale-refresh wiring**

Find `applyLocale()` (search for where `mcpSettingsSection.applyLocale()` is called, if it is — if `McpSettingsSection` doesn't currently get an explicit `applyLocale()` call from `MainFrame`'s locale-change handler, skip this; otherwise add a matching call):

```groovy
    aiLauncherSection?.applyLocale()
```

right next to the existing `mcpSettingsSection?.applyLocale()`-style call (search `applyLocale` in `MainFrame.groovy` to find the exact spot — it's invoked from the `propertyChange`/locale-change path).

- [ ] **Step 5: Full compile check**

Run: `./gradlew compileGroovy -q`
Expected: no errors.

- [ ] **Step 6: Commit** (both Task 19's and this task's changes together, since they're mutually dependent for compilation)

```bash
git add app/src/main/groovy/se/alipsa/accounting/ui/McpServerLifecycle.groovy app/src/main/groovy/se/alipsa/accounting/ui/MainFrame.groovy
git commit -m "kopplar in AiAssistantLauncherSection i MainFrame och McpServerLifecycle"
```

---

## Task 21: `release.md` entry

**Files:**
- Modify: `release.md`

- [ ] **Step 1: Add a bullet under the current in-progress version section**

Read `release.md`'s top section heading first (`head -20 release.md`) to find the current unreleased version heading, then add, under its feature list:

```markdown
- Ny inställning under "AI / MCP": "Starta AI-assistent" skriver en projekt-scopad MCP-konfiguration och instruktionsfil åt vald AI-klient (Claude Code, Codex, Kimi, Vibe) till en dedikerad arbetsyta och öppnar en terminal där — MCP-registreringen syns då bara i det sammanhanget, inte i klientens globala konfiguration.
```

(Match the existing bullet style/language in that section — check a couple of neighboring bullets first and mirror their phrasing register.)

- [ ] **Step 2: Commit**

```bash
git add release.md
git commit -m "uppdaterar release.md med AI-assistent-launcher"
```

---

## Task 22: Full verification

- [ ] **Step 1: Format**

Run: `./gradlew spotlessApply`
Then inspect `git diff` — Spotless can reformat Markdown files too; review before proceeding, and revert any unintended reformatting of files outside this feature's scope.

- [ ] **Step 2: Static analysis on the new/changed production classes**

Run: `./gradlew codenarcMain`
Expected: no new violations. Fix anything flagged in the files this plan touched before continuing.

- [ ] **Step 3: Full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (compilation, all tests, Spotless, CodeNarc).

- [ ] **Step 4: Commit any Spotless/CodeNarc fixups**

```bash
git add -A
git commit -m "kör spotlessApply och åtgärdar codenarc-anmärkningar"
```

(Skip this commit if step 1-2 produced no diff.)

- [ ] **Step 5: Manual verification checklist (not automatable — record results in the PR description)**

These are exactly the gaps the design spec's Testing section flags as impossible to cover in CI:
- On Linux: click "Detect" for a real installed terminal (e.g. `gnome-terminal` or `xterm`), fill in a real `claude`/`codex` binary path if available, click Launch, confirm a terminal window opens in the AI workspace directory running that CLI, and that the CLI successfully connects to the local MCP endpoint (check its own MCP status output).
- On macOS: same, confirming `Terminal.app` opens via the `osascript` path and the wrapper script runs correctly from a path containing a space (`Application Support`).
- On Windows: same, confirming Windows Terminal opens via `wt.exe`/`cmd.exe /v:off /c`, and that the token line in the Codex wrapper is never echoed to the terminal.
- For Claude Code, confirm the root `CLAUDE.md` is loaded and that it directs the assistant to use the accounting MCP skill. For Codex, confirm root `AGENTS.md` contains both the bookkeeping-assistant profile and the accounting MCP skill, and that the assistant follows the profile by asking for confirmation before a record-changing action.
- On Windows, if a test machine with a profile path containing a space is available (common — e.g. "C:\Users\Per Nyfelt\..."), confirm Launch still works from that account; this is the primary real-world case `TerminalCommandBuilder`'s Windows quoting (Task 10) exists to handle. A profile path containing `&`/`|`/`<`/`>`/`^` is refused outright before anything is written (`TerminalCommandBuilder` throws `IllegalArgumentException`, covered by an automated CI test) rather than launched and hoped to behave — if such an account happens to be available, confirm Launch shows the resulting error message rather than opening a terminal; not worth provisioning an account specially for this.
- **On Windows only:** after Launch (or Detect) has written into the AI workspace, inspect the NTFS permissions on `.mcp.json`, `.codex/config.toml`, and a `.launch-*` wrapper script (`icacls <path>`, or right-click → Properties → Security) and confirm only the current user has access. This is the only way to exercise `RealAclPermissionAdapter` — because `AclFileAttributeView` only exists on Windows, it has zero automated test coverage anywhere in this plan (every ACL case in `AiWorkspacePermissionsTest`, Task 6, uses a fake `AclPermissionAdapter`), so this manual check is the sole verification that owner-only file permissions are actually enforced on that platform, which is the entire point of the fail-closed permission design.
- Confirm rotating the MCP token (Settings → regenerate) purges the AI workspace and that a subsequent Launch regenerates fresh, working files.
- Confirm closing the app purges the AI workspace (inspect the directory before/after a clean shutdown).
- If a real Kimi or Vibe install is available, confirm the `AGENTS.md` instructions file is actually picked up before promoting either out of `EXPERIMENTAL` in `domain/AiClient.groovy` (flip the `experimental` flag as a small follow-up change once confirmed, per the design spec's "Client verification status" section).

---

## Self-Review Notes

- **Spec coverage:** Runtime skill source (Task 5), fixed-location workspace + test isolation override (Task 4), fail-closed POSIX/ACL permissions + symlink chain, including symlink checks *before* any traversal/permission mutation, not after (Task 6), atomic writes + AtomicMoveNotSupportedException handling + a symlink re-check immediately before both the temp-file create and the move (Task 7), per-client config content + fixtures, including the confirmed-correct Vibe `[[mcp_servers]]` array-of-tables schema (Task 9), terminal adapters incl. dropped `cmd`/`start` fallback and explicit `wt.exe`/`cmd.exe /v:off` (Task 10), wrapper script templates incl. no-`call`/forced-off-delayed-expansion/double-expansion regression/generalized env-var map (Task 11), PATH detection via injectable seams (Task 12), workspace orchestration + narrow-scope refresh + symlink-safe purge with directory-stream failures captured in `PurgeResult` rather than escaping + `isValidExecutable` (Task 13), launcher + spawn-failure cleanup + unique wrapper naming + pre-launch executable validation + self-sufficient workspace creation (Task 14), preferences (Task 15), i18n incl. the standalone `experimentalSuffix` key (Task 16), UI incl. typed terminal-adapter override, MCP-availability gating, executable-path validation before writing/spawning anything, an `experimental`-flag-driven display name, and PATH-scan/purge work backgrounded off the EDT via `BackgroundTaskRunner` (Task 17), rotation reordered to purge-then-rotate with honest partial-failure reporting, correct test isolation, and the purge itself backgrounded off the EDT via the same `BackgroundTaskRunner` (Task 18), lifecycle wiring for availability + shutdown purge (Task 19-20), release notes (Task 21), full-suite + manual verification including a Windows-only NTFS-permission spot check for the otherwise test-uncovered `RealAclPermissionAdapter` (Task 22). All spec sections have a corresponding task.
- **Placeholder scan:** no TBD/TODO markers; every step has complete, runnable code.
- **Type consistency:** `SecretFileWriter.write(Path root, Path target, byte[], SecretFileKind)` takes the workspace root as its first argument everywhere it's called (Tasks 7, 13, 14). `LaunchWrapperScript.unixContent`/`windowsContent` take a `Map<String, String> envVars`, not a `tokenOrNull`, everywhere (Tasks 11, 14). `AiWorkspaceService.refreshClientFiles(AiClient, String, String)` and `AiAssistantLauncher.launch(AiClient, Path, TerminalAdapterKind, Path, String)` are used with the same argument order and types everywhere they're called (Tasks 13, 14, 17, 18). `PurgeResult.isComplete()`/`.failed`/`.removed` are used consistently (Tasks 13, 18). `TerminalAdapterKind`/`Path` pairing is always `Tuple2<TerminalAdapterKind, Path>` with `.v1`/`.v2` accessors (Tasks 13, 17). `AiWorkspaceService`'s test constructor is `(AiWorkspacePermissions, SecretFileWriter, ExecutableProbe, EnvironmentLookup, FileDeleter)` consistently across Task 13's own tests and nowhere else constructed directly. `AiAssistantLauncher`'s test constructor is `(AiWorkspacePermissions, SecretFileWriter, ExecutableProbe, ProcessRunner)` consistently across Task 14's tests. `AiWorkspacePermissions.ensureDirectory(Path root, Path dir)` always takes the workspace root as its first argument, everywhere it's called (Tasks 6, 13, 14) — never the old single-`Path` form, which would have recursed and mutated permissions all the way to the filesystem root. `BackgroundTaskRunner.run(Closure backgroundWork, Closure onDone, Closure onError)` is defined once (Task 17, `ui` package) and reused as-is by `McpSettingsSection` (Task 18) rather than duplicated; every production constructor that takes it defaults to `new SwingBackgroundTaskRunner()`, and every test that constructs `AiAssistantLauncherSection` or `McpSettingsSection` passes the same same-thread synchronous fake instead, so assertions immediately after a background-dispatching call stay valid.
- **Review round 1 (post-implementation-review):** six findings from an implementation-readiness review were folded back in: (1) `AiClient.experimental` now actually drives the "(experimental)" UI suffix via `AiAssistantLauncherSection.displayName()` and the new `aiLauncher.experimentalSuffix` key, instead of being a flag nothing reads while two i18n strings hardcode the same text — so Task 22's "flip the flag" follow-up now has a real effect. (2) Task 22's manual checklist gained a Windows-only NTFS-permission spot check, since `RealAclPermissionAdapter` — the only thing enforcing owner-only file permissions on Windows — previously shipped with no automated *or* manual verification anywhere in the plan. (3) PATH-scanning detection (constructor auto-detect, both "Detect" buttons) and `McpSettingsSection.regenerateToken()`'s purge now run off the EDT via the new `BackgroundTaskRunner` seam, given this codebase's documented history of EDT-blocking bugs (PR #90). (4) `AiAssistantLauncher.launch()` now calls `permissions.ensureDirectory(workspace, workspace)` itself (Task 14), removing its previously-implicit reliance on `refreshClientFiles()` having been called first. (5) Wrapper-script retention after a successful launch (until the next purge) is now explicitly documented as a deliberate reliability tradeoff in `AiAssistantLauncher`'s class doc comment, not left as an unstated side effect. (6) `Files.isExecutable()` being a weaker guard on Windows than POSIX was noted as an accepted java.nio limitation, not fixable within this plan — no code change.
- **Review round 2 (independent second review):** six more findings, all with real code/test changes. (1) `TerminalCommandBuilder`'s `WINDOWS_TERMINAL` case now returns the `-d <workspace>` and `/c <script>` arguments pre-wrapped in a literal `"..."` pair rather than bare, with a doc comment explaining why (Java's Windows `ProcessBuilder` quoting alone isn't enough once `wt.exe` re-serializes a command line for its `cmd.exe` child) and two new tests covering a workspace path with a space and one with `&`; the residual `cmd.exe` metacharacter-grammar risk that quoting alone can't fully close is documented in the same comment and given an opportunistic (not mandatory) Task 22 checklist line. *(Superseded by round 3 item 2 below — quoting the `&` case turned out not to be good enough on its own.)* (2) `AtomicSecretFileWriter.write()` now re-checks `verifyNoSymlinksInPath` a third time — immediately after the atomic move, before `applyAndVerify` — since that call's chmod/ACL-apply has no portable no-follow form and so cannot refuse a symlink swapped in during the move on its own; a new test (`detectsASymlinkSwappedInImmediatelyAfterTheMoveAndRefusesToApplyPermissionsThroughIt`) proves it deterministically via a fake `FileMover` that plants the swap itself, without needing real concurrency. (3) `AiWorkspacePermissionsTest` (Task 6) was missing its `java.nio.file.attribute.PosixFilePermission` import despite using `Set<PosixFilePermission>` — added. (4) `BackgroundTaskRunner.run` (Task 17) gained a mandatory third `onError` closure — the original two-closure form let any exception from `backgroundWork` die silently on its background thread with the user seeing nothing; `SwingBackgroundTaskRunner` now catches and dispatches it to `onError` on the EDT, every call site in `AiAssistantLauncherSection` and `McpSettingsSection.regenerateToken()` (Task 18) was updated, and a new `SwingBackgroundTaskRunnerTest` case proves the propagation. (5) `AiAssistantLauncherSectionTest`'s `findLaunchButton` (Task 17) matched "first `JButton` with a listener and non-null text," which is a per-client Detect button, not the launch button, once both exist on the panel — `launchButton` is now given a stable `.name` and the test matches on that instead. (6) `onLaunch()` (Task 17) now backgrounds the entire launch workflow — executable re-validation, `refreshClientFiles`, and `AiAssistantLauncher.launch` (file writes + process spawn) — via `doLaunch()` dispatched through `backgroundTaskRunner.run(...)`, not just the earlier detection/purge calls; only the cheap, no-I/O field/dropdown reads stay synchronous on the EDT before dispatching.
- **Review round 3 (two blockers + one minor from a third review):** (1) Task 14's `launchCreatesTheWorkspaceDirectoryWhenItDoesNotAlreadyExist` test (added in round 1) could never pass as written: it pointed the workspace override at a `freshHome` that didn't exist at all, but `AiWorkspacePermissions.ensureDirectory()` (Task 6) requires the workspace root's *parent* to already exist — that's an intentional contract (this class creates the workspace root and below, never above it), not a bug to route around. Fixed by creating `freshHome` in the test and leaving only the workspace root itself (`freshHome/ai-workspace`) uncreated, which is what the test actually meant to exercise. (2) Round 2's `&`-quoting fix for `TerminalCommandBuilder` was not good enough: quoting narrows but does not close `cmd.exe`'s own command-line-grammar risk for `&`/`|`/`<`/`>`/`^`, and treating that as an accepted residual gap conflicts with the plan's fail-closed design elsewhere. `commandFor` now refuses outright (`IllegalArgumentException`) to build a `WINDOWS_TERMINAL` command line whose workspace or script path contains any of those five characters, with a test covering all five for both the workspace and script arguments. Because that refusal has to happen before any secret-bearing file is written, `AiAssistantLauncher.launch()` (Task 14) was reordered to call `TerminalCommandBuilder.commandFor(...)` *before* `secretFileWriter.write(...)`, not after, with a new test (`launchRejectsAWindowsWorkspacePathContainingACmdMetacharacterBeforeWritingAnything`) proving no wrapper file is left behind when it refuses. Task 22's checklist line and this document's own round 2 note were both updated to stop describing this as an accepted residual risk. (3) Minor: the "Type consistency" bullet above still described `BackgroundTaskRunner.run` as taking two closures after round 2 added the mandatory third `onError` closure — corrected.
- **Review round 4 (one blocker from a fourth review):** Round 3's Windows-path fail-closed check lived only inside `AiAssistantLauncher.launch()`, which `AiAssistantLauncherSection.doLaunch()` (Task 17) calls *after* `AiWorkspaceService.refreshClientFiles(...)` — so for Claude/Kimi/Vibe, whose config file directly embeds the plaintext MCP token, an unsafe Windows workspace path was rejected only *after* that token-bearing file had already been written. Round 3's own claim that "a refusal here never leaves a secret-bearing file behind" was therefore only true for the wrapper script, not the client config — and round 3's test only checked for the absence of a wrapper file, not a config file, so it didn't catch this. Fixed by extracting the workspace-only half of the check into a new public `TerminalCommandBuilder.rejectUnsafeWorkspacePathForWindowsTerminal(Path workspace)` (Task 10, with its own test) and a new `AiAssistantLauncher.validatePreflight(TerminalAdapterKind adapterKind)` instance method that calls it (Task 14, with two new tests) — checking the workspace path alone is equivalent to checking the eventual script path too, since the script's filename portion is always safe by construction (client binary name + a UUID) and the workspace path is always its prefix, so this doesn't need the script path to exist yet. `doLaunch()` (Task 17) now calls `aiAssistantLauncher.validatePreflight(adapterKind)` as its very first step, before `refreshClientFiles(...)`. Because `terminalKindCombo` only ever offers `TerminalAdapterKind.forCurrentOs()` — never `WINDOWS_TERMINAL` on a Linux/macOS test runner — `doLaunch()` was changed from `private` to `@PackageScope` so a new test (`doLaunchNeverWritesClaudesConfigWhenTheWindowsWorkspacePathIsUnsafe`) can call it directly with `AiClient.CLAUDE` + `WINDOWS_TERMINAL` and assert `.mcp.json` is never created, proving the actual gap the review found is closed, not just a proxy for it.
- **Review round 5 (two high, one medium, from a fifth review):** (1+3) Setting `VIBE_HOME` to the AI workspace (added in round 1 as "belt-and-suspenders" for an *unconfirmed* Vibe config-discovery mechanism) was wrong, not just unnecessary: `VIBE_HOME` relocates a Vibe installation's *entire* profile — config, `.env` credentials, agents, logs, skills — not just MCP server config, so every launch would have silently hidden the user's existing Vibe setup behind an empty one, likely forcing re-setup or breaking Vibe outright. Per Mistral's own configuration docs, the generated `.vibe/config.toml` is already discovered project-locally without any env var, which also resolves a design inconsistency the same round's findings pointed out: writing `AGENTS.md` at the workspace root (correct for Vibe's *project-level* instructions) while also pointing `VIBE_HOME` at that same workspace (which implies *user-level* state) were two contradictory assumptions about where Vibe should look. Fixed by deleting the `VIBE_HOME` branch from `AiAssistantLauncher.launch()` (Task 14) entirely — Vibe now gets the same empty `envVars` as Claude/Kimi — and replacing the test that asserted `VIBE_HOME` was set with `launchForVibeSetsNoEnvironmentVariablesAtAll`, a regression test asserting the Vibe wrapper contains no `export`/`VIBE_HOME` at all. (2) `binaryPath` — user-configurable, typed into Settings or PATH-detected — was rendered into the `.cmd` wrapper's `cd`/exec lines via `escapeForCmdScript()` (Task 3), which only rejected embedded `"` and doubled `%`; it did not reject `&`, `|`, `<`, `>`, `^`, the same characters `TerminalCommandBuilder` (Task 10) already fails closed on for the *outer* `cmd.exe /c` invocation — and quoting does not neutralize them on an ordinary line *inside* a batch script file either, since cmd.exe uses the identical grammar there. Fixed at the single choke point all three embedded values (workspace, binary path, every `envVars` value) already pass through: `escapeForCmdScript()` now rejects all five characters via a new shared `ProcessArgumentEscaping.UNSAFE_WINDOWS_COMMAND_CHARACTERS` constant, which `TerminalCommandBuilder` was also switched to reference instead of keeping its own separate copy of the same five characters. New tests cover the escaping utility directly (`escapeForCmdScriptRejectsEveryUnsafeCmdMetacharacter`) and `LaunchWrapperScript.windowsContent()`'s actual production code path for both an unsafe `binaryPath` and an unsafe env var value.
- **Review round 6 (one high, one medium, from a sixth review):** (1) `PathBinaryResolver` (Task 12) only ever probed the bare `<PATH entry>/<binaryName>` — but every `AiClient.binaryName` (`claude`, `codex`, `kimi`, `vibe`) is extensionless, and real Windows installs never expose a literal extensionless file, only `*.exe`/`*.cmd`/`*.bat`, so PATH-based auto-detection could never find anything on Windows at all. Fixed by having `resolve(...)` also try each extension from the `PATHEXT` environment variable (read through the same `EnvironmentLookup` seam already injected for `PATH` — no separate "is this Windows" check or seam needed, since `PATHEXT` is simply unset on Linux/macOS, which is what keeps this fully testable off Windows too), trying the bare name first as requested. New tests cover PATHEXT-based resolution, bare-name-first ordering when both would match, and that an unset `PATHEXT` behaves exactly as before. (2) `autoDetectBlankFields()` (Task 17) snapshotted which fields were blank before dispatching a background PATH scan, but its `onDone` callback then wrote detected values into those fields unconditionally — so a user who typed a custom binary or terminal path during the (automatic, EDT-blocking-avoidance-motivated) scan could have that input silently overwritten and persisted the moment the stale result arrived. Fixed by re-checking each field is *still* blank inside `onDone`, immediately before writing into it, for both the per-client binary fields and the terminal path field; the explicit per-client "Detect" buttons don't need the same re-check, since overwriting is exactly what clicking one asks for. Proven with a new `DelayedBackgroundTaskRunner` test fake (runs `backgroundWork` eagerly but defers `onDone`/`onError` until the test calls `completeAll()`, simulating the real gap a background thread + `invokeLater` leaves open) and a new test that types into the Codex field during that gap and asserts the typed value survives; each binary field also gained a stable `.name` (matching `launchButton`'s existing precedent) so the test can locate it.
- **Review round 7 (assistant profile):** The launch workspace is a purpose-built project context, so it now carries a shared bookkeeping-assistant profile as well as the MCP skill. Task 5 bundles `skill/assistant-profile.md`; Task 8 adds `assistantProfileFile(...)`; and Task 13 writes it as Claude's root `CLAUDE.md` while prepending it to the `AGENTS.md` skill document for Codex, Kimi, and Vibe. The profile defines the assistant's role, requires explicit assumptions and confirmation before changes, and directs uncertain or jurisdiction-specific advice to the authoritative web resources named by the skill. Tests pin both composition modes and Task 22 adds real-client confirmation.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-07-23-ai-assistant-launcher.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
