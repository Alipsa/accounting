package se.alipsa.accounting.domain

import static org.junit.jupiter.api.Assertions.assertEquals

import org.junit.jupiter.api.Test

class TerminalAdapterKindTest {

  @Test
  void windowsAdaptersIncludeTerminalAndCommandPrompt() {
    assertEquals([TerminalAdapterKind.WINDOWS_TERMINAL, TerminalAdapterKind.COMMAND_PROMPT],
        TerminalAdapterKind.forOsName('Windows 11'))
  }

  @Test
  void macAdapterIsUnchanged() {
    assertEquals([TerminalAdapterKind.TERMINAL_APP], TerminalAdapterKind.forOsName('Mac OS X'))
  }

  @Test
  void linuxAdaptersAreUnchanged() {
    assertEquals([TerminalAdapterKind.GNOME_TERMINAL, TerminalAdapterKind.KONSOLE, TerminalAdapterKind.XTERM],
        TerminalAdapterKind.forOsName('Linux'))
  }
}
