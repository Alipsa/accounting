package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.Test

import se.alipsa.accounting.domain.TerminalAdapterKind
import se.alipsa.accounting.support.ProcessArgumentEscaping

import java.nio.file.InvalidPathException
import java.nio.file.Path

class TerminalCommandBuilderTest {

  private static final Path EXECUTABLE = Path.of('/usr/bin/terminal')
  private static final Path WORKSPACE = Path.of('/tmp/workspace with spaces')
  private static final Path SCRIPT = WORKSPACE.resolve('.launch-codex-id.sh')

  @Test
  void createsGnomeTerminalCommand() {
    assertEquals([EXECUTABLE.toString(), '--', SCRIPT.toString()],
        TerminalCommandBuilder.commandFor(TerminalAdapterKind.GNOME_TERMINAL, EXECUTABLE, WORKSPACE, SCRIPT))
  }

  @Test
  void createsKonsoleAndXtermCommands() {
    List<String> expected = [EXECUTABLE.toString(), '-e', SCRIPT.toString()]
    assertEquals(expected, TerminalCommandBuilder.commandFor(TerminalAdapterKind.KONSOLE, EXECUTABLE, WORKSPACE, SCRIPT))
    assertEquals(expected, TerminalCommandBuilder.commandFor(TerminalAdapterKind.XTERM, EXECUTABLE, WORKSPACE, SCRIPT))
  }

  @Test
  void createsWindowsTerminalCommandWithoutEmbeddedQuotes() {
    assertEquals([EXECUTABLE.toString(), '-d', WORKSPACE.toString(), 'cmd.exe', '/v:off', '/c', SCRIPT.toString()],
        TerminalCommandBuilder.commandFor(TerminalAdapterKind.WINDOWS_TERMINAL, EXECUTABLE, WORKSPACE, SCRIPT))
  }

  @Test
  void createsCommandPromptCommandThroughStartForAVisibleWindow() {
    Path cmdScript = WORKSPACE.resolve('.launch-codex-id.cmd')
    assertEquals([EXECUTABLE.toString(), '/c', 'start', AiAssistantLauncher.ASSISTANT_SESSION_NAME,
        '/d', WORKSPACE.toString(), EXECUTABLE.toString(), '/v:off', '/c', cmdScript.toString()],
        TerminalCommandBuilder.commandFor(TerminalAdapterKind.COMMAND_PROMPT, EXECUTABLE, WORKSPACE, cmdScript))
  }

  @Test
  void createsGitBashCommandThatOpensMinttyWithATitle() {
    Path gitBash = Path.of('C:\\Program Files\\Git\\git-bash.exe')
    Path mintty = Path.of('C:\\Program Files\\Git\\usr\\bin\\mintty.exe')
    Path shScript = WORKSPACE.resolve('.launch-codex-id.sh')

    List<String> command = TerminalCommandBuilder.commandFor(TerminalAdapterKind.GIT_BASH, gitBash, WORKSPACE, shScript)

    assertEquals([mintty.toString(), '-T', AiAssistantLauncher.ASSISTANT_SESSION_NAME,
        '--dir', WORKSPACE.toString(), '/usr/bin/bash', '--login', '-i', shScript.fileName.toString()], command)
  }

  @Test
  void rejectsEveryUnsafeCharacterForGitBash() {
    ProcessArgumentEscaping.UNSAFE_WINDOWS_COMMAND_CHARACTERS.each { String unsafe ->
      assertRejectsUnsafeCharacter(TerminalAdapterKind.GIT_BASH, unsafe)
    }
  }

  @Test
  void createsTerminalAppCommand() {
    String escapedScriptPath = SCRIPT.toString().replace('\\', '\\\\')
    String script = "tell application \"Terminal\" to do script \"'${escapedScriptPath}'\""
    assertEquals([EXECUTABLE.toString(), '-e', script.toString()],
        TerminalCommandBuilder.commandFor(TerminalAdapterKind.TERMINAL_APP, EXECUTABLE, WORKSPACE, SCRIPT))
  }

  @Test
  void rejectsEveryUnsafeCharacterForWindowsTerminal() {
    ProcessArgumentEscaping.UNSAFE_WINDOWS_COMMAND_CHARACTERS.each { String unsafe ->
      assertRejectsUnsafeCharacter(TerminalAdapterKind.WINDOWS_TERMINAL, unsafe)
    }
  }

  @Test
  void rejectsEveryUnsafeCharacterForCommandPrompt() {
    ProcessArgumentEscaping.UNSAFE_WINDOWS_COMMAND_CHARACTERS.each { String unsafe ->
      assertRejectsUnsafeCharacter(TerminalAdapterKind.COMMAND_PROMPT, unsafe)
    }
  }

  private static void assertRejectsUnsafeCharacter(TerminalAdapterKind adapter, String unsafe) {
    withPathOrSkip("/tmp/work${unsafe}space") { Path unsafeWorkspace ->
      assertThrows(IllegalArgumentException) {
        TerminalCommandBuilder.commandFor(adapter, EXECUTABLE, unsafeWorkspace, SCRIPT)
      }
    }
    withPathOrSkip("/tmp/.launch-codex${unsafe}id.cmd") { Path unsafeScript ->
      assertThrows(IllegalArgumentException) {
        TerminalCommandBuilder.commandFor(adapter, EXECUTABLE, WORKSPACE, unsafeScript)
      }
    }
  }

  /**
   * On Windows, some unsafe characters (e.g. '<', '>', '|') can't even be represented in a
   * Path - the filesystem provider itself throws InvalidPathException before our own validation
   * runs. That is a strictly stronger guarantee than ours, so there is nothing left to assert.
   */
  private static void withPathOrSkip(String rawPath, Closure assertion) {
    Path path
    try {
      path = Path.of(rawPath)
    } catch (InvalidPathException ignored) {
      return
    }
    assertion.call(path)
  }
}
