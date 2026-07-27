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

import java.nio.file.Files
import java.nio.file.Path

class AiWorkspaceServiceTest {

  @TempDir
  Path tempDir

  private String previousWorkspaceHome
  private String previousUserHome

  @BeforeEach
  void captureWorkspaceHome() {
    previousWorkspaceHome = System.getProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY)
    previousUserHome = System.getProperty('user.home')
    System.setProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY, tempDir.resolve('home').toString())
  }

  @AfterEach
  void restoreWorkspaceHome() {
    if (previousWorkspaceHome == null) {
      System.clearProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY)
    } else {
      System.setProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY, previousWorkspaceHome)
    }
    System.setProperty('user.home', previousUserHome)
  }

  @Test
  void detectsClientBinaryFromPath() {
    Path expected = tempDir.resolve('bin').resolve('codex').toAbsolutePath().normalize()
    AiWorkspaceService service = service(['PATH': tempDir.resolve('bin').toString()], { Path path -> path == expected } as ExecutableProbe,
        { Path path -> Files.deleteIfExists(path) } as FileDeleter)

    assertEquals(expected, service.detectBinaryPath(AiClient.CODEX))
  }

  @Test
  void detectsKimiFromItsStandardUserDirectoryWhenItIsAbsentFromPath() {
    System.setProperty('user.home', tempDir.toString())
    Path expected = tempDir.resolve('.kimi-code').resolve('bin').resolve('kimi').toAbsolutePath().normalize()
    AiWorkspaceService service = service([:], { Path path -> path == expected } as ExecutableProbe,
        { Path path -> Files.deleteIfExists(path) } as FileDeleter)

    assertEquals(expected, service.detectBinaryPath(AiClient.KIMI))
  }

  @Test
  void refreshesCodexConfigAndInstructions() {
    Map<Path, String> files = [:]
    SecretFileWriter writer = { Path root, Path target, byte[] content, SecretFileKind kind ->
      files[target] = new String(content, 'UTF-8')
    } as SecretFileWriter
    EnvironmentLookup lookup = { String name -> null } as EnvironmentLookup
    AiWorkspaceService service = new AiWorkspaceService(new AiWorkspacePermissions(), writer,
        { Path path -> false } as ExecutableProbe, lookup, { Path path -> Files.deleteIfExists(path) } as FileDeleter)

    service.refreshClientFiles(AiClient.CODEX, 'http://127.0.0.1:8080/mcp', 'token-value')

    Path workspace = AppPaths.aiWorkspaceDirectory()
    String config = files[AiWorkspacePaths.configFile(workspace, AiClient.CODEX)]
    assertTrue(config.contains('bearer_token_env_var = "ACCOUNTING_MCP_TOKEN"'))
    assertFalse(config.contains('token-value'))
    assertTrue(files[AiWorkspacePaths.instructionsFile(workspace, AiClient.CODEX)].contains('Accounting MCP'))
  }

  @Test
  void refreshingClaudeClientFilesSeedsReadOnlyMcpPermissions() {
    Map<Path, String> files = [:]
    SecretFileWriter writer = { Path root, Path target, byte[] content, SecretFileKind kind ->
      files[target] = new String(content, 'UTF-8')
    } as SecretFileWriter
    EnvironmentLookup lookup = { String name -> null } as EnvironmentLookup
    AiWorkspaceService service = new AiWorkspaceService(new AiWorkspacePermissions(), writer,
        { Path path -> false } as ExecutableProbe, lookup, { Path path -> Files.deleteIfExists(path) } as FileDeleter)

    service.refreshClientFiles(AiClient.CLAUDE, 'http://127.0.0.1:8080/mcp', 'token-value')

    Path workspace = AppPaths.aiWorkspaceDirectory()
    String settingsLocal = files[AiWorkspacePaths.settingsLocalFile(workspace)]
    assertTrue(settingsLocal.contains('mcp__accounting__list_accounts'))
    assertTrue(settingsLocal.contains('mcp__accounting__get_active_context'))
    assertFalse(settingsLocal.contains('mcp__accounting__import_sie'))
  }

  @Test
  void refreshingClaudeClientFilesPreservesManuallyApprovedPermissionsOnRelaunch() {
    Path workspace = AppPaths.aiWorkspaceDirectory()
    Path settingsLocal = AiWorkspacePaths.settingsLocalFile(workspace)
    Files.createDirectories(settingsLocal.parent)
    Files.writeString(settingsLocal, '{"permissions": {"allow": ["mcp__accounting__close_fiscal_year", "Bash(pandoc *)"]}}')
    Map<Path, String> files = [:]
    SecretFileWriter writer = { Path root, Path target, byte[] content, SecretFileKind kind ->
      files[target] = new String(content, 'UTF-8')
    } as SecretFileWriter
    EnvironmentLookup lookup = { String name -> null } as EnvironmentLookup
    AiWorkspaceService service = new AiWorkspaceService(new AiWorkspacePermissions(), writer,
        { Path path -> false } as ExecutableProbe, lookup, { Path path -> Files.deleteIfExists(path) } as FileDeleter)

    service.refreshClientFiles(AiClient.CLAUDE, 'http://127.0.0.1:8080/mcp', 'token-value')

    String settingsContent = files[settingsLocal]
    assertTrue(settingsContent.contains('mcp__accounting__close_fiscal_year'))
    assertTrue(settingsContent.contains('Bash(pandoc *)'))
    assertTrue(settingsContent.contains('mcp__accounting__list_accounts'))
  }

  @Test
  void refreshingNonClaudeClientFilesDoesNotWriteClaudeSettings() {
    Map<Path, String> files = [:]
    SecretFileWriter writer = { Path root, Path target, byte[] content, SecretFileKind kind ->
      files[target] = new String(content, 'UTF-8')
    } as SecretFileWriter
    EnvironmentLookup lookup = { String name -> null } as EnvironmentLookup
    AiWorkspaceService service = new AiWorkspaceService(new AiWorkspacePermissions(), writer,
        { Path path -> false } as ExecutableProbe, lookup, { Path path -> Files.deleteIfExists(path) } as FileDeleter)

    service.refreshClientFiles(AiClient.CODEX, 'http://127.0.0.1:8080/mcp', 'token-value')

    Path workspace = AppPaths.aiWorkspaceDirectory()
    assertFalse(files.containsKey(AiWorkspacePaths.settingsLocalFile(workspace)))
  }

  @Test
  void refusesToReadClaudeSettingsThroughASymlink() {
    assumeTrue(!System.getProperty('os.name', '').toLowerCase(Locale.ROOT).contains('win'))
    Path workspace = AppPaths.aiWorkspaceDirectory()
    Path settingsLocal = AiWorkspacePaths.settingsLocalFile(workspace)
    Path outside = tempDir.resolve('outside-settings')
    Files.createDirectories(settingsLocal.parent)
    Files.createDirectories(outside)
    Files.createSymbolicLink(settingsLocal, outside)
    Map<Path, String> files = [:]
    SecretFileWriter writer = { Path root, Path target, byte[] content, SecretFileKind kind ->
      files[target] = new String(content, 'UTF-8')
    } as SecretFileWriter
    EnvironmentLookup lookup = { String name -> null } as EnvironmentLookup
    AiWorkspaceService service = new AiWorkspaceService(new AiWorkspacePermissions(), writer,
        { Path path -> false } as ExecutableProbe, lookup, { Path path -> Files.deleteIfExists(path) } as FileDeleter)

    assertThrows(IllegalStateException) {
      service.refreshClientFiles(AiClient.CLAUDE, 'http://127.0.0.1:8080/mcp', 'token-value')
    }
    assertFalse(files.containsKey(settingsLocal))
  }

  @Test
  void purgesAvailableSecretsAndReportsIndividualDeletionFailure() {
    Path workspace = AppPaths.aiWorkspaceDirectory()
    Path failedConfig = AiWorkspacePaths.configFile(workspace, AiClient.CODEX)
    Path wrapper = workspace.resolve('.launch-codex-id.sh')
    Files.createDirectories(failedConfig.parent)
    Files.writeString(failedConfig, 'token')
    Files.writeString(wrapper, 'token')
    AiWorkspaceService service = service([:], { Path path -> false } as ExecutableProbe, { Path path ->
      if (path == failedConfig) { throw new IOException('cannot delete config') }
      Files.deleteIfExists(path)
    } as FileDeleter)

    PurgeResult result = service.purgeAllSecrets()

    assertFalse(result.complete)
    assertEquals([wrapper], result.removed)
    assertEquals([failedConfig], result.failed)
  }

  @Test
  void refusesToPurgeThroughASymlinkedWorkspaceRoot() {
    assumeTrue(!System.getProperty('os.name', '').toLowerCase(Locale.ROOT).contains('win'))
    Path workspace = AppPaths.aiWorkspaceDirectory()
    Path target = tempDir.resolve('outside')
    Files.createDirectories(workspace.parent)
    Files.createDirectories(target)
    Files.createSymbolicLink(workspace, target)
    AiWorkspaceService service = service([:], { Path path -> false } as ExecutableProbe,
        { Path path -> Files.deleteIfExists(path) } as FileDeleter)

    PurgeResult result = service.purgeAllSecrets()

    assertFalse(result.complete)
    assertEquals([workspace], result.failed)
    assertTrue(result.removed.isEmpty())
  }

  @Test
  void prefersWindowsTerminalFromPathOverStoreAliasAndCommandPrompt() {
    Path windowsTerminal = tempDir.resolve('bin').resolve('wt.exe').toAbsolutePath().normalize()
    Path storeAlias = tempDir.resolve('appdata').resolve('Microsoft').resolve('WindowsApps').resolve('wt.exe').toAbsolutePath().normalize()
    Path commandPrompt = tempDir.resolve('bin').resolve('cmd.exe').toAbsolutePath().normalize()
    AiWorkspaceService service = service(
        ['PATH': tempDir.resolve('bin').toString(), 'LOCALAPPDATA': tempDir.resolve('appdata').toString()],
        { Path path -> path == windowsTerminal || path == storeAlias || path == commandPrompt } as ExecutableProbe,
        { Path path -> Files.deleteIfExists(path) } as FileDeleter)

    Tuple2 result = withWindowsOs { service.detectTerminalAdapter() }

    assertEquals(TerminalAdapterKind.WINDOWS_TERMINAL, result.v1)
    assertEquals(windowsTerminal, result.v2)
  }

  @Test
  void fallsBackToStoreAliasWhenWindowsTerminalIsNotOnPath() {
    Path storeAlias = tempDir.resolve('appdata').resolve('Microsoft').resolve('WindowsApps').resolve('wt.exe').toAbsolutePath().normalize()
    Path commandPrompt = tempDir.resolve('bin').resolve('cmd.exe').toAbsolutePath().normalize()
    AiWorkspaceService service = service(
        ['PATH': tempDir.resolve('bin').toString(), 'LOCALAPPDATA': tempDir.resolve('appdata').toString()],
        { Path path -> path == storeAlias || path == commandPrompt } as ExecutableProbe,
        { Path path -> Files.deleteIfExists(path) } as FileDeleter)

    Tuple2 result = withWindowsOs { service.detectTerminalAdapter() }

    assertEquals(TerminalAdapterKind.WINDOWS_TERMINAL, result.v1)
    assertEquals(storeAlias, result.v2)
  }

  @Test
  void fallsBackToCommandPromptWhenWindowsTerminalIsUnavailable() {
    Path commandPrompt = tempDir.resolve('bin').resolve('cmd.exe').toAbsolutePath().normalize()
    AiWorkspaceService service = service(
        ['PATH': tempDir.resolve('bin').toString(), 'LOCALAPPDATA': tempDir.resolve('appdata').toString()],
        { Path path -> path == commandPrompt } as ExecutableProbe,
        { Path path -> Files.deleteIfExists(path) } as FileDeleter)

    Tuple2 result = withWindowsOs { service.detectTerminalAdapter() }

    assertEquals(TerminalAdapterKind.COMMAND_PROMPT, result.v1)
    assertEquals(commandPrompt, result.v2)
  }

  @Test
  void returnsNullWhenNoWindowsTerminalAdapterOrCommandPromptResolves() {
    AiWorkspaceService service = service(
        ['PATH': tempDir.resolve('bin').toString(), 'LOCALAPPDATA': tempDir.resolve('appdata').toString()],
        { Path path -> false } as ExecutableProbe,
        { Path path -> Files.deleteIfExists(path) } as FileDeleter)

    assertEquals(null, withWindowsOs { service.detectTerminalAdapter() })
  }

  private static Tuple2 withWindowsOs(Closure<Tuple2> action) {
    String previous = System.getProperty('os.name')
    System.setProperty('os.name', 'Windows 10')
    try {
      return action.call()
    } finally {
      if (previous == null) {
        System.clearProperty('os.name')
      } else {
        System.setProperty('os.name', previous)
      }
    }
  }

  private static AiWorkspaceService service(Map<String, String> environment, ExecutableProbe probe, FileDeleter deleter) {
    EnvironmentLookup lookup = { String name -> environment[name] } as EnvironmentLookup
    SecretFileWriter writer = { Path root, Path target, byte[] content, SecretFileKind kind -> } as SecretFileWriter
    new AiWorkspaceService(new AiWorkspacePermissions(), writer, probe, lookup, deleter)
  }
}
