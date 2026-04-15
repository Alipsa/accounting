package unit.se.alipsa.accounting.domain

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull

import org.junit.jupiter.api.Test

import se.alipsa.accounting.domain.ThemeMode

class ThemeModeTest {

  @Test
  void allModesExist() {
    assertEquals(3, ThemeMode.values().length)
    assertNotNull(ThemeMode.SYSTEM)
    assertNotNull(ThemeMode.LIGHT)
    assertNotNull(ThemeMode.DARK)
  }

  @Test
  void fromNameReturnsCorrectMode() {
    assertEquals(ThemeMode.SYSTEM, ThemeMode.fromName('SYSTEM'))
    assertEquals(ThemeMode.LIGHT, ThemeMode.fromName('LIGHT'))
    assertEquals(ThemeMode.DARK, ThemeMode.fromName('DARK'))
  }

  @Test
  void fromNameReturnsSystemForNull() {
    assertEquals(ThemeMode.SYSTEM, ThemeMode.fromName(null))
  }

  @Test
  void fromNameReturnsSystemForUnknown() {
    assertEquals(ThemeMode.SYSTEM, ThemeMode.fromName('UNKNOWN'))
  }
}
