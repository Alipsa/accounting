package unit.se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import se.alipsa.accounting.domain.ThemeMode
import se.alipsa.accounting.service.UserPreferencesService

class UserPreferencesThemeTest {

  private final UserPreferencesService service = new UserPreferencesService()

  @AfterEach
  void cleanup() {
    service.setTheme(null)
    service.setAutomaticUpdateCheckEnabled(true)
    service.setLastActiveCompanyId(null)
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
}
