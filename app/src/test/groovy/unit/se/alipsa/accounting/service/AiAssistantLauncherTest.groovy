package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

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
  void keepsWrapperUntilTheWorkspaceIsPurgedAfterSuccessfulLaunch() {
    List<Path> deleted = []
    AiAssistantLauncher launcher = newLauncher({ List<String> command, Path workspace -> null } as ProcessRunner, deleted)

    launcher.launch(AiClient.CODEX, Path.of('/bin/codex'), TerminalAdapterKind.XTERM, Path.of('/bin/xterm'), 'token-value')

    assertTrue(deleted.isEmpty())
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
    assertTrue(Files.notExists(deleted.first()))
  }

  @Test
  void commandPromptWritesCmdWrapperAndRunsDirectCommand() {
    List<String> capturedCommand = []
    Map<Path, String> writtenScripts = [:]
    SecretFileWriter writer = { Path root, Path target, byte[] content, SecretFileKind kind ->
      writtenScripts[target] = new String(content, 'UTF-8')
    } as SecretFileWriter
    ExecutableProbe probe = { Path path -> true } as ExecutableProbe
    AiAssistantLauncher launcher = new AiAssistantLauncher(new AiWorkspacePermissions(), writer, probe,
        { List<String> command, Path workspace -> capturedCommand = command; null } as ProcessRunner,
        { Path path -> Files.deleteIfExists(path) } as FileDeleter)

    launcher.launch(AiClient.CODEX, Path.of('C:\\bin\\codex.exe'), TerminalAdapterKind.COMMAND_PROMPT,
        Path.of('C:\\Windows\\System32\\cmd.exe'), 'token-value')

    assertEquals(1, writtenScripts.size())
    Path script = writtenScripts.keySet().first()
    assertTrue(script.toString().endsWith('.cmd'))
    assertEquals(['C:\\Windows\\System32\\cmd.exe', '/c', 'start', AiAssistantLauncher.ASSISTANT_SESSION_NAME,
        '/d', AppPaths.aiWorkspaceDirectory().toString(), 'C:\\Windows\\System32\\cmd.exe', '/v:off', '/c', script.toString()],
        capturedCommand)
  }

  @Test
  void gitBashGetsAPosixWrapperAndLaunchesMinttyWithTitleAndWorkingDirectory() {
    List<String> capturedCommand = []
    Map<Path, String> writtenScripts = [:]
    SecretFileWriter writer = { Path root, Path target, byte[] content, SecretFileKind kind ->
      writtenScripts[target] = new String(content, 'UTF-8')
    } as SecretFileWriter
    ExecutableProbe probe = { Path path -> true } as ExecutableProbe
    AiAssistantLauncher launcher = new AiAssistantLauncher(new AiWorkspacePermissions(), writer, probe,
        { List<String> command, Path workspace -> capturedCommand = command; null } as ProcessRunner,
        { Path path -> Files.deleteIfExists(path) } as FileDeleter)

    Path gitBash = Path.of('C:\\Program Files\\Git\\git-bash.exe')
    Path mintty = Path.of('C:\\Program Files\\Git\\usr\\bin\\mintty.exe')
    launcher.launch(AiClient.CODEX, Path.of('C:\\bin\\codex.exe'), TerminalAdapterKind.GIT_BASH,
        gitBash, 'token-value')

    assertEquals(1, writtenScripts.size())
    Path script = writtenScripts.keySet().first()
    assertTrue(script.toString().endsWith('.sh'))
    String content = writtenScripts.values().first()
    assertTrue(content.startsWith('#!/bin/sh'))
    // Not "exec": exec would replace the shell process, leaving nothing to pause on failure with -
    // and the Git Bash terminal window closes the instant the script (or what it ran) exits.
    assertFalse(content.contains('exec '))
    assertTrue(content.contains("'C:\\bin\\codex.exe'"))
    assertTrue(content.contains('Exit code:'))
    Path workspace = AppPaths.aiWorkspaceDirectory()
    assertEquals([mintty.toString(), '-T', AiAssistantLauncher.ASSISTANT_SESSION_NAME,
        '--dir', workspace.toString(), '/usr/bin/bash', '--login', '-i', script.fileName.toString()], capturedCommand)
  }

  @Test
  void failsPreflightBeforeWritingWrapperWhenWorkspacePathIsUnsafe() {
    System.setProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY, tempDir.resolve('work&space').toString())
    List<Path> written = []
    SecretFileWriter writer = { Path root, Path target, byte[] content, SecretFileKind kind ->
      written << target
    } as SecretFileWriter
    ExecutableProbe probe = { Path path -> true } as ExecutableProbe
    AiAssistantLauncher launcher = new AiAssistantLauncher(new AiWorkspacePermissions(), writer, probe,
        { List<String> command, Path workspace -> null } as ProcessRunner,
        { Path path -> Files.deleteIfExists(path) } as FileDeleter)

    assertThrows(IllegalArgumentException) {
      launcher.validatePreflight(TerminalAdapterKind.COMMAND_PROMPT)
    }
    assertTrue(written.isEmpty())
  }

  @Test
  void failsPreflightBeforeWritingWrapperWhenWindowsTerminalWorkspacePathIsUnsafe() {
    // '^' rather than '|': both are unsafe Windows command characters, but '|' is illegal in an
    // actual Windows path, so tempDir.resolve() itself would throw before reaching the code under test.
    System.setProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY, tempDir.resolve('work^space').toString())
    List<Path> written = []
    SecretFileWriter writer = { Path root, Path target, byte[] content, SecretFileKind kind ->
      written << target
    } as SecretFileWriter
    ExecutableProbe probe = { Path path -> true } as ExecutableProbe
    AiAssistantLauncher launcher = new AiAssistantLauncher(new AiWorkspacePermissions(), writer, probe,
        { List<String> command, Path workspace -> null } as ProcessRunner,
        { Path path -> Files.deleteIfExists(path) } as FileDeleter)

    assertThrows(IllegalArgumentException) {
      launcher.validatePreflight(TerminalAdapterKind.WINDOWS_TERMINAL)
    }
    assertTrue(written.isEmpty())
  }

  @Test
  void failsPreflightBeforeWritingWrapperWhenGitBashWorkspacePathIsUnsafe() {
    System.setProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY, tempDir.resolve('work^space').toString())
    List<Path> written = []
    SecretFileWriter writer = { Path root, Path target, byte[] content, SecretFileKind kind ->
      written << target
    } as SecretFileWriter
    ExecutableProbe probe = { Path path -> true } as ExecutableProbe
    AiAssistantLauncher launcher = new AiAssistantLauncher(new AiWorkspacePermissions(), writer, probe,
        { List<String> command, Path workspace -> null } as ProcessRunner,
        { Path path -> Files.deleteIfExists(path) } as FileDeleter)

    assertThrows(IllegalArgumentException) {
      launcher.validatePreflight(TerminalAdapterKind.GIT_BASH)
    }
    assertTrue(written.isEmpty())
  }

  @Test
  void gitBashLaunchesFromBashExeAndFindsMinttyTwoLevelsUp() {
    List<String> capturedCommand = []
    Map<Path, String> writtenScripts = [:]
    SecretFileWriter writer = { Path root, Path target, byte[] content, SecretFileKind kind ->
      writtenScripts[target] = new String(content, 'UTF-8')
    } as SecretFileWriter
    ExecutableProbe probe = { Path path -> true } as ExecutableProbe
    AiAssistantLauncher launcher = new AiAssistantLauncher(new AiWorkspacePermissions(), writer, probe,
        { List<String> command, Path workspace -> capturedCommand = command; null } as ProcessRunner,
        { Path path -> Files.deleteIfExists(path) } as FileDeleter)

    Path bash = Path.of('C:\\Program Files\\Git\\bin\\bash.exe')
    Path mintty = Path.of('C:\\Program Files\\Git\\usr\\bin\\mintty.exe')
    launcher.launch(AiClient.CODEX, Path.of('C:\\bin\\codex.exe'), TerminalAdapterKind.GIT_BASH,
        bash, 'token-value')

    assertEquals(1, writtenScripts.size())
    Path script = writtenScripts.keySet().first()
    assertTrue(script.toString().endsWith('.sh'))
    Path workspace = AppPaths.aiWorkspaceDirectory()
    assertEquals([mintty.toString(), '-T', AiAssistantLauncher.ASSISTANT_SESSION_NAME,
        '--dir', workspace.toString(), '/usr/bin/bash', '--login', '-i', script.fileName.toString()], capturedCommand)
  }

  @Test
  void failsEarlyWhenGitBashAdapterHasNoMinttyNextToIt() {
    Path codex = Path.of('C:\\bin\\codex.exe')
    Path gitBash = Path.of('C:\\Program Files\\Git\\git-bash.exe')
    Path mintty = Path.of('C:\\Program Files\\Git\\usr\\bin\\mintty.exe')
    List<Path> written = []
    SecretFileWriter writer = { Path root, Path target, byte[] content, SecretFileKind kind ->
      written << target
    } as SecretFileWriter
    ExecutableProbe probe = { Path path -> path == codex || path == gitBash } as ExecutableProbe
    AiAssistantLauncher launcher = new AiAssistantLauncher(new AiWorkspacePermissions(), writer, probe,
        { List<String> command, Path workspace -> null } as ProcessRunner,
        { Path path -> Files.deleteIfExists(path) } as FileDeleter)

    IllegalArgumentException exception = assertThrows(IllegalArgumentException) {
      launcher.launch(AiClient.CODEX, codex, TerminalAdapterKind.GIT_BASH, gitBash, 'token-value')
    }

    assertTrue(exception.message.contains('mintty.exe'))
    assertTrue(exception.message.contains(mintty.toString()))
    assertTrue(written.isEmpty())
  }

  @Test
  void failsEarlyWhenBashExeAdapterHasNoMinttyInGitRoot() {
    Path codex = Path.of('C:\\bin\\codex.exe')
    Path bash = Path.of('C:\\Program Files\\Git\\bin\\bash.exe')
    Path mintty = Path.of('C:\\Program Files\\Git\\usr\\bin\\mintty.exe')
    List<Path> written = []
    SecretFileWriter writer = { Path root, Path target, byte[] content, SecretFileKind kind ->
      written << target
    } as SecretFileWriter
    ExecutableProbe probe = { Path path -> path == codex || path == bash } as ExecutableProbe
    AiAssistantLauncher launcher = new AiAssistantLauncher(new AiWorkspacePermissions(), writer, probe,
        { List<String> command, Path workspace -> null } as ProcessRunner,
        { Path path -> Files.deleteIfExists(path) } as FileDeleter)

    IllegalArgumentException exception = assertThrows(IllegalArgumentException) {
      launcher.launch(AiClient.CODEX, codex, TerminalAdapterKind.GIT_BASH, bash, 'token-value')
    }

    assertTrue(exception.message.contains('mintty.exe'))
    assertTrue(exception.message.contains(mintty.toString()))
    assertTrue(written.isEmpty())
  }

  @Test
  void launchesClaudeWithTheAssistantSessionName() {
    Map<Path, String> writtenScripts = [:]
    SecretFileWriter writer = { Path root, Path target, byte[] content, SecretFileKind kind ->
      writtenScripts[target] = new String(content, 'UTF-8')
    } as SecretFileWriter
    ExecutableProbe probe = { Path path -> true } as ExecutableProbe
    AiAssistantLauncher launcher = new AiAssistantLauncher(new AiWorkspacePermissions(), writer, probe,
        { List<String> command, Path workspace -> null } as ProcessRunner,
        { Path path -> Files.deleteIfExists(path) } as FileDeleter)

    launcher.launch(AiClient.CLAUDE, Path.of('/bin/claude'), TerminalAdapterKind.XTERM, Path.of('/bin/xterm'), 'token-value')

    assertTrue(writtenScripts.values().first().contains("'--name' 'Alipsa Accounting AI Assistant'"))
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
