# AI Assistant Launcher Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Launch AI Assistant" feature to the Settings UI that writes a project-scoped MCP config + skill/instructions file for a chosen AI CLI (Claude Code, Codex, Kimi, Mistral Vibe) into a dedicated, fixed-location, permission-hardened workspace, then spawns the user's terminal running that CLI there.

**Architecture:** A new `ai-workspace` directory (fixed OS-default per-user location, independent of the app's configurable/shared data home) holds per-client config + instructions files, refreshed on each launch. `AiWorkspaceService` owns writing/detection/cleanup; `AiAssistantLauncher` renders a per-launch wrapper script and spawns a terminal adapter to run it. All filesystem writes are atomic and permission-verified (fail-closed, POSIX or Windows ACL); all process spawning uses `ProcessBuilder(List<String>)` with no shell string ever built from secret/user input.

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
- `service/AiWorkspacePaths.groovy` — pure path resolution (config/instructions/wrapper paths).
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
- `ui/AiAssistantLauncherSection.groovy` — new Settings UI section.

**Modified main files:**
- `app/build.gradle` — bundle `skill/` as a classpath resource.
- `support/AppPaths.groovy` — add `aiWorkspaceDirectory()` + `AI_WORKSPACE_HOME_OVERRIDE_PROPERTY`.
- `service/UserPreferencesService.groovy` — add binary-path / terminal-adapter-kind / terminal-path keys.
- `ui/McpSettingsSection.groovy` — constructor gains `AiWorkspaceService`; rotation handler reordered (purge-then-rotate).
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
- Produces: `static String shellQuoteSingle(String)`, `static String appleScriptEscape(String)`, `static String escapeForCmdScript(String)` (throws `IllegalArgumentException` on embedded `"`).

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
    value.replace('%', '%%')
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "se.alipsa.accounting.support.ProcessArgumentEscapingTest"`
Expected: PASS (10 tests)

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

## Task 5: Bundle the skill file as a classpath resource

**Files:**
- Modify: `app/build.gradle`

**Interfaces:**
- Produces: `accounting-mcp.md` becomes readable via `SomeClass.getResourceAsStream('/accounting-mcp.md')` from anywhere in `app`'s runtime classpath.

- [ ] **Step 1: Make the change**

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

- [ ] **Step 2: Verify the resource is on the classpath**

Run: `./gradlew compileGroovy processResources -q && find app/build/resources/main -maxdepth 1 -iname "accounting-mcp.md"`
Expected: prints `app/build/resources/main/accounting-mcp.md` — confirming the file is now in the compiled resources output (this is what makes `getResourceAsStream('/accounting-mcp.md')` work at runtime; Task 12's `AiWorkspaceServiceTest` gives this its first real behavioral test).

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle
git commit -m "bundlar skill/ som en classpath-resurs"
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
- Produces: `enum SecretFileKind { EXECUTABLE, DATA }`. `interface AclPermissionAdapter { void applyOwnerOnly(Path); void verifyOwnerOnly(Path) }`. `class AiWorkspacePermissions` with a no-arg constructor (real ACL adapter) and a `(AclPermissionAdapter)` constructor (for tests), and methods: `void ensureDirectory(Path dir)`, `void createFileWithPermissions(Path path, SecretFileKind kind)`, `void createFileWithPermissions(Path path, SecretFileKind kind, Set<String> supportedViews)`, `void applyAndVerify(Path path, SecretFileKind kind)`, `void applyAndVerify(Path path, SecretFileKind kind, Set<String> supportedViews)`, `void verifyNoSymlinksInPath(Path root, Path candidate)`. Later tasks (`AtomicSecretFileWriter`, `AiWorkspaceService`, `AiAssistantLauncher`) depend on exactly these method names/signatures.

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
import java.nio.file.attribute.PosixFilePermissions

class AiWorkspacePermissionsTest {

  @TempDir
  Path tempDir

  private final AiWorkspacePermissions permissions = new AiWorkspacePermissions()

  @Test
  void ensureDirectoryCreatesOwnerOnlyPermissionsRecursively() {
    assumePosixSupported()
    Path nested = tempDir.resolve('ai-workspace').resolve('.codex')

    permissions.ensureDirectory(nested)

    Set expected = PosixFilePermissions.fromString('rwx------')
    assertEquals(expected, Files.getPosixFilePermissions(nested))
    assertEquals(expected, Files.getPosixFilePermissions(nested.parent))
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
      permissions.ensureDirectory(linked)
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

  void ensureDirectory(Path dir) {
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
    // Always recurse into the parent, even if it already exists as a directory: this guarantees
    // every ancestor gets a fresh symlink check on every call, rather than skipping the check for
    // an already-existing parent (which would reopen the same TOCTOU window this method exists to close).
    Path parent = dir.parent
    if (parent != null) {
      ensureDirectory(parent)
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
Expected: PASS (9 tests; the two symlink tests are skipped via `assumeTrue` on a Windows runner, if ever run there)

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
- Produces: `interface SecretFileWriter { void write(Path root, Path target, byte[] content, SecretFileKind kind) }` — `root` is the AI workspace boundary, so the writer itself (not just its callers) verifies the whole path is symlink-free, immediately before creating the temp file *and* again immediately before the atomic move (the move is the actual commit point, so it gets its own fresh check). Real implementation `AtomicSecretFileWriter` with a no-arg constructor and a `(AiWorkspacePermissions, FileMover)` constructor for tests. Later tasks (`AiWorkspaceService`, `AiAssistantLauncher`) construct `new AtomicSecretFileWriter()` and depend on the `SecretFileWriter.write(root, target, ...)` signature.

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
 * permission verification and a symlink-chain check both before the temp
 * file is created and again immediately before the atomic move (the move is
 * the real commit point, so it gets its own fresh check rather than relying
 * solely on the earlier one).
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
      permissions.applyAndVerify(target, kind)
    } finally {
      Files.deleteIfExists(tempFile)
    }
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "se.alipsa.accounting.service.AtomicSecretFileWriterTest"`
Expected: PASS (4 tests)

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
- Produces: `static Path configFile(Path workspace, AiClient client)`, `static Path instructionsFile(Path workspace, AiClient client)`, `static Path wrapperScript(Path workspace, AiClient client, String launchId, boolean windows)`.

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

  static Path wrapperScript(Path workspace, AiClient client, String launchId, boolean windows) {
    workspace.resolve(".launch-${client.binaryName}-${launchId}.${windows ? 'cmd' : 'sh'}")
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "se.alipsa.accounting.service.AiWorkspacePathsTest"`
Expected: PASS (4 tests)

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
- Produces: `static List<String> commandFor(TerminalAdapterKind kind, Path executable, Path workspace, Path script)`.

- [ ] **Step 1: Write the failing test**

```groovy
package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
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
        [wtExe.toString(), '-d', workspace.toString(), 'cmd.exe', '/v:off', '/c', winScript.toString()],
        TerminalCommandBuilder.commandFor(TerminalAdapterKind.WINDOWS_TERMINAL, wtExe, workspace, winScript))
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

/** Builds the exact process argument list for each known terminal adapter kind. See design spec, "Terminal adapters". */
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
        return [executable.toString(), '-d', workspace.toString(), 'cmd.exe', '/v:off', '/c', script.toString()]
      case TerminalAdapterKind.TERMINAL_APP:
        String quotedScript = ProcessArgumentEscaping.shellQuoteSingle(script.toString())
        String appleScriptSource = 'tell application "Terminal" to do script "' +
            ProcessArgumentEscaping.appleScriptEscape(quotedScript) + '"'
        return [executable.toString(), '-e', appleScriptSource]
      default:
        throw new IllegalArgumentException("Unknown terminal adapter kind: ${kind}")
    }
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "se.alipsa.accounting.service.TerminalCommandBuilderTest"`
Expected: PASS (4 tests)

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
- Produces: `static String unixContent(Path workspace, Path binaryPath, Map<String, String> envVars)`, `static String windowsContent(Path workspace, Path binaryPath, Map<String, String> envVars)`. `envVars` is an ordered map of extra environment variables to `export`/`set` before the `cd`/invocation lines — empty for Claude/Kimi (no secret, no extra var), `[ACCOUNTING_MCP_TOKEN: token]` for Codex, `[VIBE_HOME: workspace/.vibe path]` for Vibe (see Task 14 — Vibe's config discovery mechanism is unconfirmed by Mistral's own docs as project-local-vs-home, so the wrapper sets `VIBE_HOME` pointing at the same `.vibe/config.toml` we already write, covering both possible discovery mechanisms at once). A generic map (not a single `tokenOrNull`) is used because more than one client can need an env var, and some of those aren't secrets.

- [ ] **Step 1: Write the failing test**

```groovy
package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
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
    Map<String, String> envVars = new LinkedHashMap<>()
    envVars.VIBE_HOME = '/home/per/.local/share/alipsa-accounting/ai-workspace/.vibe'
    envVars.OTHER_VAR = 'value'
    String content = LaunchWrapperScript.unixContent(workspace, binaryPath, envVars)
    List<String> lines = content.split('\n', -1) as List<String>

    assertEquals("export VIBE_HOME='/home/per/.local/share/alipsa-accounting/ai-workspace/.vibe'".toString(), lines[1])
    assertEquals("export OTHER_VAR='value'".toString(), lines[2])
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
Expected: PASS (7 tests)

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
- Produces: `interface EnvironmentLookup { String getenv(String name) }`, `interface ExecutableProbe { boolean isExecutableFile(Path candidate) }`, `class PathBinaryResolver { PathBinaryResolver(EnvironmentLookup, ExecutableProbe); Path resolve(String binaryName) }`. `AiWorkspaceService` (Task 13) constructs and uses `PathBinaryResolver`.

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

/** Resolves a binary name to an absolute path by scanning PATH, via injectable environment/filesystem seams. */
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
    for (String directory : path.split(java.io.File.pathSeparator)) {
      if (!directory) {
        continue
      }
      Path candidate = Paths.get(directory, binaryName)
      if (executableProbe.isExecutableFile(candidate)) {
        return candidate.toAbsolutePath().normalize()
      }
    }
    null
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "se.alipsa.accounting.service.PathBinaryResolverTest"`
Expected: PASS (3 tests)

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
  void refreshClientFilesWritesConfigAndCopiesSkillInstructions() {
    AiWorkspaceService service = new AiWorkspaceService()

    service.refreshClientFiles(AiClient.CODEX, 'http://127.0.0.1:48652/mcp', 'unused-for-codex')

    Path workspace = AppPaths.aiWorkspaceDirectory()
    Path configFile = workspace.resolve('.codex/config.toml')
    Path instructionsFile = workspace.resolve('AGENTS.md')
    assertTrue(Files.exists(configFile))
    assertTrue(configFile.text.contains('bearer_token_env_var = "ACCOUNTING_MCP_TOKEN"'))
    assertTrue(Files.exists(instructionsFile))
    assertTrue(instructionsFile.text.length() > 0)
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
Expected: FAIL — classes not found. This is also the first real exercise of Task 5's classpath-resource change: if `accounting-mcp.md` weren't on the classpath, `refreshClientFilesWritesConfigAndCopiesSkillInstructions` would fail with `IllegalStateException` from the resource-missing check below, not just a missing-class error.

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
    permissions.ensureDirectory(AppPaths.aiWorkspaceDirectory())
  }

  void refreshClientFiles(AiClient client, String endpoint, String token) {
    Path workspace = AppPaths.aiWorkspaceDirectory()
    ensureWorkspace()

    Path configFile = AiWorkspacePaths.configFile(workspace, client)
    // Verify BEFORE creating/traversing the parent chain, and again right before the write
    // (AtomicSecretFileWriter.write also re-checks immediately before its own atomic move —
    // see Task 7 — so this is deliberate, cheap, layered defense, not redundant by accident).
    permissions.verifyNoSymlinksInPath(workspace, configFile)
    permissions.ensureDirectory(configFile.parent)
    permissions.verifyNoSymlinksInPath(workspace, configFile)
    String configText = AiClientConfigWriter.configContent(client, endpoint, token)
    secretFileWriter.write(workspace, configFile, configText.getBytes('UTF-8'), SecretFileKind.DATA)

    Path instructionsFile = AiWorkspacePaths.instructionsFile(workspace, client)
    permissions.verifyNoSymlinksInPath(workspace, instructionsFile)
    permissions.ensureDirectory(instructionsFile.parent)
    permissions.verifyNoSymlinksInPath(workspace, instructionsFile)
    secretFileWriter.write(workspace, instructionsFile, skillResourceBytes(), SecretFileKind.DATA)
  }

  private static byte[] skillResourceBytes() {
    InputStream stream = AiWorkspaceService.getResourceAsStream('/accounting-mcp.md')
    if (stream == null) {
      throw new IllegalStateException('The accounting-mcp.md skill resource is missing from the classpath.')
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
Expected: PASS (8 tests, on a Linux CI runner; the OS-name assumptions skip the suite cleanly elsewhere)

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
- Produces: `interface ProcessRunner { Process run(List<String> command, Path workingDirectory) }`. `class AiAssistantLauncher` with a no-arg constructor and a `(AiWorkspacePermissions, SecretFileWriter, ExecutableProbe, ProcessRunner)` constructor, method `void launch(AiClient client, Path binaryPath, TerminalAdapterKind adapterKind, Path adapterExecutable, String token)` — this now validates both `binaryPath` and `adapterExecutable` are real executable files *before* writing the wrapper or spawning anything, throwing `IllegalArgumentException` if not (this is the authoritative check; `AiWorkspaceService.isValidExecutable`, Task 13, additionally lets the UI, Task 17, give a friendlier per-field error before even calling `launch`). `AiAssistantLauncherSection` (Task 17) depends on this exact `launch(...)` signature.

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
    new AiWorkspacePermissions().ensureDirectory(AppPaths.aiWorkspaceDirectory())
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
  void launchForVibeSetsVibeHomeInsteadOfTheAccountingToken() {
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
    assertTrue(content.contains('export VIBE_HOME='))
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
    boolean windows = adapterKind == TerminalAdapterKind.WINDOWS_TERMINAL
    String launchId = UUID.randomUUID().toString()
    Path script = AiWorkspacePaths.wrapperScript(workspace, client, launchId, windows)
    permissions.verifyNoSymlinksInPath(workspace, script)

    Map<String, String> envVars = new LinkedHashMap<>()
    if (client == AiClient.CODEX) {
      envVars.ACCOUNTING_MCP_TOKEN = token
    } else if (client == AiClient.VIBE) {
      // Belt-and-suspenders for Vibe's unconfirmed project-local-vs-VIBE_HOME config discovery
      // (see design spec / Task 9) — VIBE_HOME points at the same .vibe/config.toml we already
      // write, so whichever mechanism Vibe actually uses, it finds the right file.
      envVars.VIBE_HOME = workspace.resolve('.vibe').toString()
    }
    String content = windows
        ? LaunchWrapperScript.windowsContent(workspace, binaryPath, envVars)
        : LaunchWrapperScript.unixContent(workspace, binaryPath, envVars)
    secretFileWriter.write(workspace, script, content.getBytes('UTF-8'), SecretFileKind.EXECUTABLE)

    List<String> command = TerminalCommandBuilder.commandFor(adapterKind, adapterExecutable, workspace, script)
    try {
      processRunner.run(command, workspace)
    } catch (Exception exception) {
      deleteWrapperBestEffort(script)
      throw exception
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
Expected: PASS (6 tests)

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
- Produces: the i18n keys `ui/AiAssistantLauncherSection.groovy` (Task 17) reads via `I18n.instance.getString(...)`/`format(...)`.

- [ ] **Step 1: Add English keys**

In `app/src/main/resources/i18n/messages.properties`, right after the existing `settings.mcp.status.unavailable=...` line (~line 871), add:

```properties
aiClient.CLAUDE=Claude Code
aiClient.CODEX=Codex
aiClient.KIMI=Kimi (experimental)
aiClient.VIBE=Vibe (experimental)
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
```

- [ ] **Step 2: Add Swedish keys**

In `app/src/main/resources/i18n/messages_sv.properties`, at the same location, add:

```properties
aiClient.CLAUDE=Claude Code
aiClient.CODEX=Codex
aiClient.KIMI=Kimi (experimentell)
aiClient.VIBE=Vibe (experimentell)
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

## Task 17: `AiAssistantLauncherSection` (new Settings UI section)

**Files:**
- Create: `app/src/main/groovy/se/alipsa/accounting/ui/AiAssistantLauncherSection.groovy`
- Test: `app/src/test/groovy/unit/se/alipsa/accounting/ui/AiAssistantLauncherSectionTest.groovy`

**Interfaces:**
- Consumes: `AiClient`/`TerminalAdapterKind` (Tasks 1-2), `UserPreferencesService` (Task 15), `AiWorkspaceService` (Task 13), `AiAssistantLauncher` (Task 14), `LoopbackMcpServer.ENDPOINT` (existing).
- Produces: `class AiAssistantLauncherSection` with constructor `(UserPreferencesService, AiWorkspaceService, AiAssistantLauncher)`, `JPanel getPanel()`, `void setMcpAvailable(boolean)`, `void applyLocale()`. `McpServerLifecycle` (Task 19) and `MainFrame` (Task 20) depend on this exact constructor and these method names.

- [ ] **Step 1: Write the failing test**

```groovy
package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.Test

import se.alipsa.accounting.domain.AiClient
import se.alipsa.accounting.service.AiAssistantLauncher
import se.alipsa.accounting.service.AiWorkspaceService
import se.alipsa.accounting.service.UserPreferencesService

import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel

import java.util.prefs.Preferences

class AiAssistantLauncherSectionTest {

  @Test
  void launchButtonStartsDisabledAndIsEnabledOnceMcpIsAvailable() {
    Preferences node = Preferences.userRoot().node("alipsa-accounting-test-${UUID.randomUUID()}")
    try {
      UserPreferencesService userPreferencesService = new UserPreferencesService(node)
      AiWorkspaceService aiWorkspaceService = new AiWorkspaceService()
      AiAssistantLauncher aiAssistantLauncher = new AiAssistantLauncher()
      AiAssistantLauncherSection section = new AiAssistantLauncherSection(userPreferencesService, aiWorkspaceService, aiAssistantLauncher)

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
          userPreferencesService, new AiWorkspaceService(), new AiAssistantLauncher())

      JComboBox<AiClient> combo = findClientCombo(section.panel)

      assert combo.itemCount == 4
    } finally {
      node.removeNode()
    }
  }

  private static JButton findLaunchButton(JPanel root) {
    findComponent(root, JButton) { JButton button -> button.actionListeners.length > 0 && button.text != null }
  }

  private static JComboBox findClientCombo(JPanel root) {
    findComponent(root, JComboBox) { JComboBox combo -> combo.itemCount == 4 }
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

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "se.alipsa.accounting.ui.AiAssistantLauncherSectionTest"`
Expected: FAIL — `AiAssistantLauncherSection` class not found.

- [ ] **Step 3: Write the implementation**

```groovy
package se.alipsa.accounting.ui

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

  private final UserPreferencesService userPreferencesService
  private final AiWorkspaceService aiWorkspaceService
  private final AiAssistantLauncher aiAssistantLauncher
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
    this.userPreferencesService = userPreferencesService
    this.aiWorkspaceService = aiWorkspaceService
    this.aiAssistantLauncher = aiAssistantLauncher
    panel.layout = new BoxLayout(panel, BoxLayout.Y_AXIS)
    panel.border = border
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
      binaryLabels[client].text = I18n.instance.format('aiLauncher.label.binaryPath', I18n.instance.getString("aiClient.${client.name()}"))
      detectBinaryButtons[client].text = I18n.instance.getString('aiLauncher.button.detect')
    }
    terminalKindLabel.text = I18n.instance.getString('aiLauncher.label.terminalAdapter')
    detectTerminalButton.text = I18n.instance.getString('aiLauncher.button.detect')
    clientLabel.text = I18n.instance.getString('aiLauncher.label.client')
    launchButton.text = I18n.instance.getString('aiLauncher.button.launch')
  }

  private void buildRows() {
    AiClient.values().each { AiClient client ->
      JLabel label = new JLabel()
      binaryLabels[client] = label
      JTextField field = new JTextField(userPreferencesService.getAiBinaryPath(client) ?: '', 24)
      binaryFields[client] = field
      JButton detect = new JButton()
      detect.addActionListener {
        Path resolved = aiWorkspaceService.detectBinaryPath(client)
        if (resolved != null) {
          field.text = resolved.toString()
          userPreferencesService.setAiBinaryPath(client, resolved.toString())
        }
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
      Tuple2<TerminalAdapterKind, Path> detected = aiWorkspaceService.detectTerminalAdapter()
      if (detected != null) {
        terminalKindCombo.selectedItem = detected.v1
        terminalPathField.text = detected.v2.toString()
        userPreferencesService.terminalAdapterKind = detected.v1
        userPreferencesService.terminalPath = detected.v2.toString()
      }
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

  private void autoDetectBlankFields() {
    AiClient.values().each { AiClient client ->
      if (!binaryFields[client].text?.trim()) {
        Path resolved = aiWorkspaceService.detectBinaryPath(client)
        if (resolved != null) {
          binaryFields[client].text = resolved.toString()
          userPreferencesService.setAiBinaryPath(client, resolved.toString())
        }
      }
    }
    if (!terminalPathField.text?.trim()) {
      Tuple2<TerminalAdapterKind, Path> detected = aiWorkspaceService.detectTerminalAdapter()
      if (detected != null) {
        terminalKindCombo.selectedItem = detected.v1
        terminalPathField.text = detected.v2.toString()
        userPreferencesService.terminalAdapterKind = detected.v1
        userPreferencesService.terminalPath = detected.v2.toString()
      }
    }
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
      showError(I18n.instance.format('aiLauncher.error.binaryMissing', I18n.instance.getString("aiClient.${client.name()}")))
      return
    }
    if (adapterKind == null || !terminalPathText) {
      showError(I18n.instance.getString('aiLauncher.error.terminalAdapterMissing'))
      return
    }
    Path binaryPathCandidate = Paths.get(binaryPathText)
    if (!aiWorkspaceService.isValidExecutable(binaryPathCandidate)) {
      showError(I18n.instance.format('aiLauncher.error.binaryNotExecutable', I18n.instance.getString("aiClient.${client.name()}"), binaryPathText))
      return
    }
    Path terminalPathCandidate = Paths.get(terminalPathText)
    if (!aiWorkspaceService.isValidExecutable(terminalPathCandidate)) {
      showError(I18n.instance.format('aiLauncher.error.terminalNotExecutable', terminalPathText))
      return
    }
    userPreferencesService.setAiBinaryPath(client, binaryPathText)
    userPreferencesService.terminalAdapterKind = adapterKind
    userPreferencesService.terminalPath = terminalPathText
    try {
      String token = userPreferencesService.ensureMcpToken()
      aiWorkspaceService.refreshClientFiles(client, LoopbackMcpServer.ENDPOINT, token)
      aiAssistantLauncher.launch(client, Paths.get(binaryPathText), adapterKind, Paths.get(terminalPathText), token)
    } catch (Exception exception) {
      showError(I18n.instance.format('aiLauncher.error.launchFailed', exception.message ?: exception.class.simpleName))
    }
  }

  private void showError(String message) {
    JOptionPane.showMessageDialog(panel, message, I18n.instance.getString('aiLauncher.error.title'), JOptionPane.ERROR_MESSAGE)
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "se.alipsa.accounting.ui.AiAssistantLauncherSectionTest"`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/groovy/se/alipsa/accounting/ui/AiAssistantLauncherSection.groovy \
        app/src/test/groovy/unit/se/alipsa/accounting/ui/AiAssistantLauncherSectionTest.groovy
git commit -m "lägger till AiAssistantLauncherSection"
```

---

## Task 18: `McpSettingsSection` — inject `AiWorkspaceService`, reorder token rotation

**Files:**
- Modify: `app/src/main/groovy/se/alipsa/accounting/ui/McpSettingsSection.groovy`
- Create: `app/src/test/groovy/unit/se/alipsa/accounting/ui/McpSettingsSectionTest.groovy`

**Interfaces:**
- Consumes: `AiWorkspaceService`/`PurgeResult` (Task 13).
- Produces: `McpSettingsSection(UserPreferencesService, AiWorkspaceService)` (new 2-arg constructor — the 1-arg one used by `MainFrame` today is removed). `McpServerLifecycle`/`MainFrame` (Tasks 19-20) must pass the new second argument.

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

      McpSettingsSection section = new McpSettingsSection(userPreferencesService, aiWorkspaceService)
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
Expected: FAIL — 2-arg constructor and `regenerateToken()` don't exist yet.

- [ ] **Step 3: Modify the implementation**

In `McpSettingsSection.groovy`:
1. Add `import se.alipsa.accounting.service.AiWorkspaceService`.
2. Add a field `private final AiWorkspaceService aiWorkspaceService`.
3. Change the constructor signature and body:

```groovy
  McpSettingsSection(UserPreferencesService userPreferencesService, AiWorkspaceService aiWorkspaceService) {
    this.userPreferencesService = userPreferencesService
    this.aiWorkspaceService = aiWorkspaceService
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

5. Add a new public method (extracted so the test above can call it directly, and so the reorder logic lives in one obvious place):

```groovy
  void regenerateToken() {
    se.alipsa.accounting.service.PurgeResult result = aiWorkspaceService.purgeAllSecrets()
    if (!result.complete) {
      javax.swing.JOptionPane.showMessageDialog(
          panel,
          I18n.instance.format('settings.mcp.rotateFailed', result.failed.join(', ')),
          I18n.instance.getString('settings.mcp.rotateFailedTitle'),
          javax.swing.JOptionPane.ERROR_MESSAGE)
      return
    }
    tokenField.text = userPreferencesService.regenerateMcpToken()
  }
```

- [ ] **Step 4: Add the two new i18n keys this uses**

In `messages.properties`:
```properties
settings.mcp.rotateFailedTitle=Could not rotate the MCP token
settings.mcp.rotateFailed=Some AI-workspace files could not be removed and still use the old token: {0}. Fix the listed files' permissions and try again.
```

In `messages_sv.properties`:
```properties
settings.mcp.rotateFailedTitle=Det gick inte att generera en ny MCP-token
settings.mcp.rotateFailed=Vissa filer i AI-arbetsytan kunde inte tas bort och anv\u00e4nder fortfarande den gamla token: {0}. \u00c5tg\u00e4rda r\u00e4ttigheterna f\u00f6r de listade filerna och f\u00f6rs\u00f6k igen.
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
- Confirm rotating the MCP token (Settings → regenerate) purges the AI workspace and that a subsequent Launch regenerates fresh, working files.
- Confirm closing the app purges the AI workspace (inspect the directory before/after a clean shutdown).
- If a real Kimi or Vibe install is available, confirm the `AGENTS.md` instructions file is actually picked up before promoting either out of `EXPERIMENTAL` in `domain/AiClient.groovy` (flip the `experimental` flag as a small follow-up change once confirmed, per the design spec's "Client verification status" section).

---

## Self-Review Notes

- **Spec coverage:** Runtime skill source (Task 5), fixed-location workspace + test isolation override (Task 4), fail-closed POSIX/ACL permissions + symlink chain, including symlink checks *before* any traversal/permission mutation, not after (Task 6), atomic writes + AtomicMoveNotSupportedException handling + a symlink re-check immediately before both the temp-file create and the move (Task 7), per-client config content + fixtures, including the confirmed-correct Vibe `[[mcp_servers]]` array-of-tables schema (Task 9), terminal adapters incl. dropped `cmd`/`start` fallback and explicit `wt.exe`/`cmd.exe /v:off` (Task 10), wrapper script templates incl. no-`call`/forced-off-delayed-expansion/double-expansion regression/generalized env-var map for Vibe's `VIBE_HOME` (Task 11), PATH detection via injectable seams (Task 12), workspace orchestration + narrow-scope refresh + symlink-safe purge with directory-stream failures captured in `PurgeResult` rather than escaping + `isValidExecutable` (Task 13), launcher + spawn-failure cleanup + unique wrapper naming + Vibe's `VIBE_HOME` env var + pre-launch executable validation (Task 14), preferences (Task 15), i18n (Task 16), UI incl. typed terminal-adapter override, MCP-availability gating, and executable-path validation before writing/spawning anything (Task 17), rotation reordered to purge-then-rotate with honest partial-failure reporting and correct test isolation (Task 18), lifecycle wiring for availability + shutdown purge (Task 19-20), release notes (Task 21), full-suite + manual verification (Task 22). All spec sections have a corresponding task.
- **Placeholder scan:** no TBD/TODO markers; every step has complete, runnable code.
- **Type consistency:** `SecretFileWriter.write(Path root, Path target, byte[], SecretFileKind)` takes the workspace root as its first argument everywhere it's called (Tasks 7, 13, 14). `LaunchWrapperScript.unixContent`/`windowsContent` take a `Map<String, String> envVars`, not a `tokenOrNull`, everywhere (Tasks 11, 14). `AiWorkspaceService.refreshClientFiles(AiClient, String, String)` and `AiAssistantLauncher.launch(AiClient, Path, TerminalAdapterKind, Path, String)` are used with the same argument order and types everywhere they're called (Tasks 13, 14, 17, 18). `PurgeResult.isComplete()`/`.failed`/`.removed` are used consistently (Tasks 13, 18). `TerminalAdapterKind`/`Path` pairing is always `Tuple2<TerminalAdapterKind, Path>` with `.v1`/`.v2` accessors (Tasks 13, 17). `AiWorkspaceService`'s test constructor is `(AiWorkspacePermissions, SecretFileWriter, ExecutableProbe, EnvironmentLookup, FileDeleter)` consistently across Task 13's own tests and nowhere else constructed directly. `AiAssistantLauncher`'s test constructor is `(AiWorkspacePermissions, SecretFileWriter, ExecutableProbe, ProcessRunner)` consistently across Task 14's tests.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-07-23-ai-assistant-launcher.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
