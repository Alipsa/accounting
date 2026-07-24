package se.alipsa.accounting.support

/** Escaping helpers for generated shell, batch, and AppleScript source. */
final class ProcessArgumentEscaping {

  static final List<String> UNSAFE_WINDOWS_COMMAND_CHARACTERS = ['&', '|', '<', '>', '^']

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
      throw new IllegalArgumentException('Value cannot be safely represented in a Windows batch script because it contains a double quote.')
    }
    UNSAFE_WINDOWS_COMMAND_CHARACTERS.each { String unsafe ->
      if (value.contains(unsafe)) {
        throw new IllegalArgumentException("Value cannot be safely represented in a Windows batch script because it contains '${unsafe}'.")
      }
    }
    value.replace('%', '%%')
  }
}
