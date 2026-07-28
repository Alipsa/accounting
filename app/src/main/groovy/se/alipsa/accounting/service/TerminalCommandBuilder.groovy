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
        // git-bash.exe is the user-facing Git-for-Windows launcher, but it is just a small wrapper
        // that runs usr\bin\mintty.exe internally. Launch mintty directly so we can set a persistent
        // window title with -T (mintty's --Title) and avoid relying on git-bash.exe to forward
        // trailing arguments to the inner bash. The script filename is passed rather than a Windows
        // absolute path because mintty's --dir sets the working directory and MSYS2 path conversion
        // can mangle backslash-style paths.
        Path mintty = minttyForGitBash(executable)
        return [mintty.toString(), '-T', AiAssistantLauncher.ASSISTANT_SESSION_NAME,
            '--dir', workspace.toString(), '/usr/bin/bash', '--login', '-i', script.fileName.toString()]
      case TerminalAdapterKind.TERMINAL_APP:
        String quoted = ProcessArgumentEscaping.shellQuoteSingle(script.toString())
        return [executable.toString(), '-e', 'tell application "Terminal" to do script "' +
            ProcessArgumentEscaping.appleScriptEscape(quoted) + '"']
      default:
        throw new IllegalArgumentException("Unknown terminal adapter kind: ${kind}")
    }
  }

  static void rejectUnsafeWorkspacePathForWindows(Path workspace) { rejectUnsafeWindowsPath(workspace) }

  /**
   * git-bash.exe lives in the Git install root, so mintty.exe is next to it under
   * usr/bin/mintty.exe. Users sometimes configure bash.exe instead (Git/bin/bash.exe);
   * in that case mintty.exe is two levels up from bash.exe under the same usr/bin.
   */
  static Path minttyForGitBash(Path gitBashExecutable) {
    Path parent = gitBashExecutable.parent
    if (parent != null && gitBashExecutable.fileName.toString().equalsIgnoreCase('bash.exe')
        && parent.fileName?.toString()?.equalsIgnoreCase('bin')) {
      Path gitRoot = parent.parent
      if (gitRoot != null) {
        return gitRoot.resolve('usr/bin/mintty.exe')
      }
    }
    gitBashExecutable.resolveSibling('usr/bin/mintty.exe')
  }

  private static void rejectUnsafeWindowsPath(Path path) {
    ProcessArgumentEscaping.UNSAFE_WINDOWS_COMMAND_CHARACTERS.each { String unsafe ->
      if (path.toString().contains(unsafe)) {
        throw new IllegalArgumentException("Refusing to launch through cmd.exe: ${path} contains '${unsafe}'.")
      }
    }
  }
}
