package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue
import static org.junit.jupiter.api.Assumptions.assumeTrue

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.AiClient
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

  private static AiWorkspaceService service(Map<String, String> environment, ExecutableProbe probe, FileDeleter deleter) {
    EnvironmentLookup lookup = { String name -> environment[name] } as EnvironmentLookup
    SecretFileWriter writer = { Path root, Path target, byte[] content, SecretFileKind kind -> } as SecretFileWriter
    new AiWorkspaceService(new AiWorkspacePermissions(), writer, probe, lookup, deleter)
  }
}
