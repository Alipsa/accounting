package unit.se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import se.alipsa.accounting.service.UserPreferencesService

class UserPreferencesDataLocationTest {

  private final UserPreferencesService service = new UserPreferencesService()

  @AfterEach
  void cleanup() {
    service.clearDataLocation()
    service.clearPendingMigration()
  }

  @Test
  void dataLocationIsNullByDefault() {
    assertNull(service.getDataLocation())
  }

  @Test
  void roundTripsDataLocation() {
    service.setDataLocation('/mnt/accounting-share')
    assertEquals('/mnt/accounting-share', service.getDataLocation())
  }

  @Test
  void clearDataLocationRemovesPreference() {
    service.setDataLocation('/mnt/accounting-share')
    service.clearDataLocation()
    assertNull(service.getDataLocation())
  }

  @Test
  void pendingMigrationIsAbsentByDefault() {
    assertNull(service.getPendingMigrationTarget())
    assertFalse(service.isPendingMigrationMove())
  }

  @Test
  void roundTripsPendingMigrationWithMoveFlag() {
    service.setPendingMigration('/mnt/accounting-share', true)
    assertEquals('/mnt/accounting-share', service.getPendingMigrationTarget())
    assertTrue(service.isPendingMigrationMove())
  }

  @Test
  void roundTripsPendingMigrationWithoutMoveFlag() {
    service.setPendingMigration('/mnt/accounting-share', false)
    assertEquals('/mnt/accounting-share', service.getPendingMigrationTarget())
    assertFalse(service.isPendingMigrationMove())
  }

  @Test
  void clearPendingMigrationRemovesPreference() {
    service.setPendingMigration('/mnt/accounting-share', true)
    service.clearPendingMigration()
    assertNull(service.getPendingMigrationTarget())
    assertFalse(service.isPendingMigrationMove())
  }
}
