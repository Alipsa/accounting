package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.AiClient
import se.alipsa.accounting.domain.TerminalAdapterKind
import se.alipsa.accounting.support.AppPaths

import java.lang.reflect.UndeclaredThrowableException
import java.nio.file.Files
import java.nio.file.Path

class AiAssistantLauncherTest {

  @TempDir
  Path tempDir

  private String previousWorkspaceHome

  @BeforeEach
  void captureWorkspaceHome() {
    previousWorkspaceHome = System.getProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY)
    System.setProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY, tempDir.resolve('home').toString())
  }

  @AfterEach
  void restoreWorkspaceHome() {
    if (previousWorkspaceHome == null) {
      System.clearProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY)
    } else {
      System.setProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY, previousWorkspaceHome)
    }
  }

  @Test
  void deletesTokenBearingWrapperAfterSuccessfulLaunch() {
    List<Path> deleted = []
    AiAssistantLauncher launcher = newLauncher({ List<String> command, Path workspace -> null } as ProcessRunner, deleted)

    launcher.launch(AiClient.CODEX, Path.of('/bin/codex'), TerminalAdapterKind.XTERM, Path.of('/bin/xterm'), 'token-value')

    assertEquals(1, deleted.size())
    assertFalse(Files.exists(deleted.first()))
  }

  @Test
  void deletesWrapperWhenLaunchingTerminalFails() {
    List<Path> deleted = []
    AiAssistantLauncher launcher = newLauncher({ List<String> command, Path workspace ->
      throw new IOException('terminal unavailable')
    } as ProcessRunner, deleted)

    UndeclaredThrowableException exception = assertThrows(UndeclaredThrowableException) {
      launcher.launch(AiClient.CODEX, Path.of('/bin/codex'), TerminalAdapterKind.XTERM, Path.of('/bin/xterm'), 'token-value')
    }

    assertEquals(IOException, exception.cause.class)
    assertEquals(1, deleted.size())
    assertFalse(Files.exists(deleted.first()))
  }

  private static AiAssistantLauncher newLauncher(ProcessRunner runner, List<Path> deleted) {
    SecretFileWriter writer = { Path root, Path target, byte[] content, SecretFileKind kind ->
      Files.write(target, content)
    } as SecretFileWriter
    ExecutableProbe probe = { Path path -> true } as ExecutableProbe
    FileDeleter deleter = { Path path ->
      deleted << path
      Files.deleteIfExists(path)
    } as FileDeleter
    new AiAssistantLauncher(new AiWorkspacePermissions(), writer, probe, runner, deleter)
  }
}
