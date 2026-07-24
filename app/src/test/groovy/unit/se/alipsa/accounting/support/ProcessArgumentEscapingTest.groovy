package se.alipsa.accounting.support

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows

import org.junit.jupiter.api.Test

class ProcessArgumentEscapingTest {

  @Test
  void safelyQuotesUnixShellValues() {
    assertEquals("'it'\\''s'", ProcessArgumentEscaping.shellQuoteSingle("it's"))
    assertEquals('\'$(whoami)\'', ProcessArgumentEscaping.shellQuoteSingle('$(whoami)'))
  }

  @Test
  void escapesAppleScriptSyntax() {
    assertEquals('say \\"hi\\"', ProcessArgumentEscaping.appleScriptEscape('say "hi"'))
    assertEquals('a\\\\b', ProcessArgumentEscaping.appleScriptEscape('a\\b'))
  }

  @Test
  void cmdEscapingDoublesPercentAndRejectsOperators() {
    assertEquals('%%PATH%%', ProcessArgumentEscaping.escapeForCmdScript('%PATH%'))
    assertThrows(IllegalArgumentException) { ProcessArgumentEscaping.escapeForCmdScript('bad"value') }
    ProcessArgumentEscaping.UNSAFE_WINDOWS_COMMAND_CHARACTERS.each { String character ->
      assertThrows(IllegalArgumentException) { ProcessArgumentEscaping.escapeForCmdScript("unsafe${character}value") }
    }
  }
}
