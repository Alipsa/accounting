package se.alipsa.accounting.service

import se.alipsa.accounting.domain.TerminalAdapterKind
import se.alipsa.accounting.support.ProcessArgumentEscaping

import java.nio.file.Path

/** Builds fixed terminal-adapter command argument lists. */
final class TerminalCommandBuilder {

  private TerminalCommandBuilder() {
  }

  static List<String> commandFor(TerminalAdapterKind kind, Path executable, Path workspace, Path script) {
    switch (kind) {
      case TerminalAdapterKind.GNOME_TERMINAL:
        return [executable.toString(), '--', script.toString()]
      case TerminalAdapterKind.KONSOLE:
      case TerminalAdapterKind.XTERM:
        return [executable.toString(), '-e', script.toString()]
      case TerminalAdapterKind.WINDOWS_TERMINAL:
        rejectUnsafeWindowsPath(workspace)
        rejectUnsafeWindowsPath(script)
        return [executable.toString(), '-d', workspace.toString(), 'cmd.exe', '/v:off', '/c', script.toString()]
      case TerminalAdapterKind.COMMAND_PROMPT:
        rejectUnsafeWindowsPath(workspace)
        rejectUnsafeWindowsPath(script)
        // A plain "cmd.exe /c script" started via ProcessBuilder gets no visible console window at
        // all in several common setups (e.g. when the launching process is itself console-attached,
        // as during development, or with certain "default terminal app" configurations) - the click
        // looks like it does nothing. Routing through "start" with an explicit title reliably forces
        // a real, independent console window regardless of the launcher's own console state.
        return [executable.toString(), '/c', 'start', AiAssistantLauncher.ASSISTANT_SESSION_NAME,
            '/d', workspace.toString(), executable.toString(), '/v:off', '/c', script.toString()]
      case TerminalAdapterKind.GIT_BASH:
        rejectUnsafeWindowsPath(workspace)
        rejectUnsafeWindowsPath(script)
        // git-bash.exe is the Git-for-Windows launcher that opens the MSYS2/Mintty terminal.
        // It is not a console-subsystem binary, so it creates its own window; we do not need
        // cmd.exe's "start". Pass only the script filename because git-bash.exe forwards extra
        // arguments to the inner bash, and a Windows-style absolute path would be mangled by
        // MSYS2 path conversion. --cd=<workspace> sets the working directory for the session.
        return [executable.toString(), '--cd=' + workspace, script.fileName.toString()]
      case TerminalAdapterKind.TERMINAL_APP:
        String quoted = ProcessArgumentEscaping.shellQuoteSingle(script.toString())
        return [executable.toString(), '-e', 'tell application "Terminal" to do script "' +
            ProcessArgumentEscaping.appleScriptEscape(quoted) + '"']
      default:
        throw new IllegalArgumentException("Unknown terminal adapter kind: ${kind}")
    }
  }

  static void rejectUnsafeWorkspacePathForWindows(Path workspace) { rejectUnsafeWindowsPath(workspace) }

  private static void rejectUnsafeWindowsPath(Path path) {
    ProcessArgumentEscaping.UNSAFE_WINDOWS_COMMAND_CHARACTERS.each { String unsafe ->
      if (path.toString().contains(unsafe)) {
        throw new IllegalArgumentException("Refusing to launch through cmd.exe: ${path} contains '${unsafe}'.")
      }
    }
  }
}
