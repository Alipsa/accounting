package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import se.alipsa.accounting.domain.TerminalAdapterKind

import java.util.prefs.Preferences

class UserPreferencesServiceTest {

  private Preferences preferences
  private UserPreferencesService service

  @BeforeEach
  void setUp() {
    preferences = Preferences.userNodeForPackage(UserPreferencesServiceTest).node(UUID.randomUUID().toString())
    service = new UserPreferencesService(preferences)
  }

  @AfterEach
  void tearDown() {
    preferences.removeNode()
  }

  @Test
  void terminalPathsAreStoredIndependentlyPerAdapterKind() {
    service.setTerminalPath(TerminalAdapterKind.WINDOWS_TERMINAL, 'C:\\Tools\\wt.exe')
    service.setTerminalPath(TerminalAdapterKind.COMMAND_PROMPT, 'C:\\WINDOWS\\system32\\cmd.exe')

    assertEquals('C:\\Tools\\wt.exe', service.getTerminalPath(TerminalAdapterKind.WINDOWS_TERMINAL))
    assertEquals('C:\\WINDOWS\\system32\\cmd.exe', service.getTerminalPath(TerminalAdapterKind.COMMAND_PROMPT))
  }

  @Test
  void switchingToAnAdapterKindWithNoStoredPathReturnsNull() {
    service.setTerminalPath(TerminalAdapterKind.WINDOWS_TERMINAL, 'C:\\Tools\\wt.exe')

    assertNull(service.getTerminalPath(TerminalAdapterKind.COMMAND_PROMPT))
  }

  @Test
  void legacySinglePathPreferenceIsHonoredOnlyForTheKindItWasSavedUnder() {
    // Simulates an install from before terminal paths were split per adapter kind.
    preferences.put('ai.launcher.terminalPath', 'C:\\WINDOWS\\system32\\cmd.exe')
    service.terminalAdapterKind = TerminalAdapterKind.COMMAND_PROMPT

    assertEquals('C:\\WINDOWS\\system32\\cmd.exe', service.getTerminalPath(TerminalAdapterKind.COMMAND_PROMPT))
    assertNull(service.getTerminalPath(TerminalAdapterKind.WINDOWS_TERMINAL))
  }

  @Test
  void perKindPathTakesPrecedenceOverTheLegacyPreference() {
    preferences.put('ai.launcher.terminalPath', 'C:\\stale\\wt.exe')
    service.terminalAdapterKind = TerminalAdapterKind.WINDOWS_TERMINAL
    service.setTerminalPath(TerminalAdapterKind.WINDOWS_TERMINAL, 'C:\\Tools\\wt.exe')

    assertEquals('C:\\Tools\\wt.exe', service.getTerminalPath(TerminalAdapterKind.WINDOWS_TERMINAL))
  }
}
