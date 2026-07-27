package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows

import org.junit.jupiter.api.Test

import se.alipsa.accounting.domain.TerminalAdapterKind
import se.alipsa.accounting.support.ProcessArgumentEscaping

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
  void createsCommandPromptCommand() {
    Path cmdScript = WORKSPACE.resolve('.launch-codex-id.cmd')
    assertEquals([EXECUTABLE.toString(), '/v:off', '/c', cmdScript.toString()],
        TerminalCommandBuilder.commandFor(TerminalAdapterKind.COMMAND_PROMPT, EXECUTABLE, WORKSPACE, cmdScript))
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
      Path unsafeWorkspace = Path.of("/tmp/work${unsafe}space")
      assertThrows(IllegalArgumentException) {
        TerminalCommandBuilder.commandFor(TerminalAdapterKind.WINDOWS_TERMINAL, EXECUTABLE, unsafeWorkspace, SCRIPT)
      }
      Path unsafeScript = Path.of("/tmp/.launch-codex${unsafe}id.cmd")
      assertThrows(IllegalArgumentException) {
        TerminalCommandBuilder.commandFor(TerminalAdapterKind.WINDOWS_TERMINAL, EXECUTABLE, WORKSPACE, unsafeScript)
      }
    }
  }

  @Test
  void rejectsEveryUnsafeCharacterForCommandPrompt() {
    ProcessArgumentEscaping.UNSAFE_WINDOWS_COMMAND_CHARACTERS.each { String unsafe ->
      Path unsafeWorkspace = Path.of("/tmp/work${unsafe}space")
      assertThrows(IllegalArgumentException) {
        TerminalCommandBuilder.commandFor(TerminalAdapterKind.COMMAND_PROMPT, EXECUTABLE, unsafeWorkspace, SCRIPT)
      }
      Path unsafeScript = Path.of("/tmp/.launch-codex${unsafe}id.cmd")
      assertThrows(IllegalArgumentException) {
        TerminalCommandBuilder.commandFor(TerminalAdapterKind.COMMAND_PROMPT, EXECUTABLE, WORKSPACE, unsafeScript)
      }
    }
  }
}
