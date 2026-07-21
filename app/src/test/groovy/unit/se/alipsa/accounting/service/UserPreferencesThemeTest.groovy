package unit.se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import se.alipsa.accounting.domain.ThemeMode
import se.alipsa.accounting.service.UserPreferencesService

import java.util.prefs.Preferences

class UserPreferencesThemeTest {

  private Preferences node
  private UserPreferencesService service

  @BeforeEach
  void setUp() {
    node = Preferences.userRoot().node("accounting-test-${UUID.randomUUID()}")
    service = new UserPreferencesService(node)
  }

  @AfterEach
  void cleanup() {
    node.removeNode()
  }

  @Test
  void defaultThemeIsSystem() {
    service.setTheme(null)
    assertEquals(ThemeMode.SYSTEM, service.getTheme())
  }

  @Test
  void roundTripsTheme() {
    service.setTheme(ThemeMode.DARK)
    assertEquals(ThemeMode.DARK, service.getTheme())

    service.setTheme(ThemeMode.LIGHT)
    assertEquals(ThemeMode.LIGHT, service.getTheme())
  }

  @Test
  void setSystemRemovesPreference() {
    service.setTheme(ThemeMode.DARK)
    service.setTheme(ThemeMode.SYSTEM)
    assertEquals(ThemeMode.SYSTEM, service.getTheme())
  }

  @Test
  void automaticUpdateCheckDefaultsToEnabled() {
    service.setAutomaticUpdateCheckEnabled(true)
    assertEquals(true, service.isAutomaticUpdateCheckEnabled())
  }

  @Test
  void roundTripsAutomaticUpdateCheckPreference() {
    service.setAutomaticUpdateCheckEnabled(false)
    assertEquals(false, service.isAutomaticUpdateCheckEnabled())

    service.setAutomaticUpdateCheckEnabled(true)
    assertEquals(true, service.isAutomaticUpdateCheckEnabled())
  }

  @Test
  void roundTripsLastActiveCompanyPreference() {
    service.setLastActiveCompanyId(17L)
    assertEquals(17L, service.getLastActiveCompanyId())

    service.setLastActiveCompanyId(null)
    assertEquals(null, service.getLastActiveCompanyId())
  }


  @Test
  void roundTripsLastActiveFiscalYearPreference() {
    service.setLastActiveFiscalYearId(42L)
    assertEquals(42L, service.getLastActiveFiscalYearId())

    service.setLastActiveFiscalYearId(null)
    assertEquals(null, service.getLastActiveFiscalYearId())
  }
}
