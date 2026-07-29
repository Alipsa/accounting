package se.alipsa.accounting.domain

/** A known terminal emulator invocation convention. */
enum TerminalAdapterKind {

  GNOME_TERMINAL('gnome-terminal'),
  KONSOLE('konsole'),
  XTERM('xterm'),
  WINDOWS_TERMINAL('wt.exe'),
  COMMAND_PROMPT('cmd.exe'),
  GIT_BASH('git-bash.exe'),
  TERMINAL_APP('osascript')

  final String defaultBinaryName

  TerminalAdapterKind(String defaultBinaryName) {
    this.defaultBinaryName = defaultBinaryName
  }

  static List<TerminalAdapterKind> forOsName(String osName) {
    String normalized = (osName ?: '').toLowerCase(Locale.ROOT)
    if (normalized.contains('win')) {
      return [WINDOWS_TERMINAL, COMMAND_PROMPT, GIT_BASH]
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
