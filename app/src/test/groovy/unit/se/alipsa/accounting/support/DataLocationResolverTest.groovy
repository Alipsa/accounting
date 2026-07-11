package unit.se.alipsa.accounting.support

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.service.UserPreferencesService
import se.alipsa.accounting.support.AppPaths
import se.alipsa.accounting.support.DataLocationResolver

import java.nio.file.Files
import java.nio.file.Path

class DataLocationResolverTest {

  @TempDir
  Path tempDir

  private final UserPreferencesService preferences = new UserPreferencesService()

  @AfterEach
  void cleanup() {
    preferences.clearDataLocation()
    preferences.clearPendingMigration()
  }

  @Test
  void resolvesToDefaultWhenNothingConfigured() {
    DataLocationResolver.Outcome outcome = DataLocationResolver.resolve(preferences)

    assertNull(outcome.location)
    assertTrue(outcome.reachable)
    assertNull(outcome.migrationNote)
    assertFalse(outcome.migrationFailed)
  }

  @Test
  void resolvesToConfiguredLocationWhenValid() {
    Path location = Files.createDirectories(tempDir.resolve('shared'))
    preferences.setDataLocation(location.toString())

    DataLocationResolver.Outcome outcome = DataLocationResolver.resolve(preferences)

    assertEquals(location.toString(), outcome.location)
    assertTrue(outcome.reachable)
  }

  @Test
  void reportsUnreachableWhenConfiguredLocationIsInvalid() {
    Path notADirectory = tempDir.resolve('a-file')
    Files.createFile(notADirectory)
    preferences.setDataLocation(notADirectory.toString())

    DataLocationResolver.Outcome outcome = DataLocationResolver.resolve(preferences)

    assertEquals(notADirectory.toString(), outcome.location)
    assertFalse(outcome.reachable)
    assertNotNull(outcome.reachabilityError)
  }

  @Test
  void appliesPendingMoveMigrationAndUpdatesDataLocation() {
    Path oldHome = Files.createDirectories(tempDir.resolve('old-home'))
    Path oldDataDir = Files.createDirectories(AppPaths.dataDirectory(oldHome))
    Files.write(oldDataDir.resolve('accounting.mv.db'), 'db-bytes'.bytes)
    preferences.setDataLocation(oldHome.toString())
    Path newHome = tempDir.resolve('new-home')
    preferences.setPendingMigration(newHome.toString(), true)

    DataLocationResolver.Outcome outcome = DataLocationResolver.resolve(preferences)

    assertEquals(newHome.toString(), outcome.location)
    assertTrue(outcome.reachable)
    assertNotNull(outcome.migrationNote)
    assertFalse(outcome.migrationFailed)
    assertEquals(newHome.toString(), preferences.getDataLocation())
    assertNull(preferences.getPendingMigrationTarget())
    assertTrue(Files.exists(AppPaths.dataDirectory(newHome).resolve('accounting.mv.db')))
  }

  @Test
  void appliesPendingPointOnlyMigrationWithoutCopyingFiles() {
    Path oldHome = Files.createDirectories(tempDir.resolve('old-home2'))
    preferences.setDataLocation(oldHome.toString())
    Path newHome = Files.createDirectories(tempDir.resolve('already-shared'))
    preferences.setPendingMigration(newHome.toString(), false)

    DataLocationResolver.Outcome outcome = DataLocationResolver.resolve(preferences)

    assertEquals(newHome.toString(), outcome.location)
    assertEquals(newHome.toString(), preferences.getDataLocation())
    assertNull(preferences.getPendingMigrationTarget())
  }

  @Test
  void failedPendingMoveMigrationLeavesOldLocationInEffect() {
    Path oldHome = Files.createDirectories(tempDir.resolve('old-home3'))
    Path oldDataDir = Files.createDirectories(AppPaths.dataDirectory(oldHome))
    Files.write(oldDataDir.resolve('accounting.mv.db'), 'db-bytes'.bytes)
    preferences.setDataLocation(oldHome.toString())
    Path invalidTarget = tempDir.resolve('invalid-target')
    Files.createFile(invalidTarget)
    preferences.setPendingMigration(invalidTarget.toString(), true)

    DataLocationResolver.Outcome outcome = DataLocationResolver.resolve(preferences)

    assertEquals(oldHome.toString(), outcome.location)
    assertTrue(outcome.reachable)
    assertNotNull(outcome.migrationNote)
    assertTrue(outcome.migrationFailed)
    assertEquals(oldHome.toString(), preferences.getDataLocation())
    assertNull(preferences.getPendingMigrationTarget())
    assertTrue(Files.exists(oldDataDir.resolve('accounting.mv.db')))
  }

  @Test
  void pendingMoveMigrationFailsWithoutOrphaningDataWhenOldHomeIsUnreachable() {
    // Simulates the configured location being a network mount that isn't currently connected:
    // the old home path is not an existing directory at all.
    Path oldHome = tempDir.resolve('unmounted-share')
    preferences.setDataLocation(oldHome.toString())
    Path newHome = tempDir.resolve('fresh-new-home')
    preferences.setPendingMigration(newHome.toString(), true)

    DataLocationResolver.Outcome outcome = DataLocationResolver.resolve(preferences)

    assertFalse(outcome.reachable)
    assertTrue(outcome.migrationFailed)
    assertNotNull(outcome.migrationNote)
    assertEquals(oldHome.toString(), outcome.location)
    assertEquals(oldHome.toString(), preferences.getDataLocation())
    assertNull(preferences.getPendingMigrationTarget())
    assertFalse(Files.exists(newHome))
  }
}
