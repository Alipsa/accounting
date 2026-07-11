package se.alipsa.accounting.support

import groovy.transform.TupleConstructor

import se.alipsa.accounting.service.UserPreferencesService

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Decides which application data location to use at startup, applying any
 * pending migration first. Pure filesystem/preferences logic with no Swing
 * or database dependency, so it can run before {@code DatabaseService} is
 * initialized.
 */
final class DataLocationResolver {

  private DataLocationResolver() {
  }

  static Outcome resolve(UserPreferencesService preferences) {
    MigrationApplication migration = applyPendingMigrationIfAny(preferences)
    String migrationNote = migration?.note
    boolean migrationFailed = migration != null && !migration.success

    String location = preferences.getDataLocation()
    if (!location) {
      return Outcome.resolved(null, migrationNote, migrationFailed)
    }
    DataLocationMigrator.ValidationResult validation = DataLocationMigrator.validateExistingLocation(Paths.get(location))
    if (!validation.valid) {
      return Outcome.unreachable(location, validation.reason, migrationNote, migrationFailed)
    }
    Outcome.resolved(location, migrationNote, migrationFailed)
  }

  private static MigrationApplication applyPendingMigrationIfAny(UserPreferencesService preferences) {
    String pendingTarget = preferences.getPendingMigrationTarget()
    if (!pendingTarget) {
      return null
    }
    Path oldHome = effectiveHome(preferences.getDataLocation())
    boolean move = preferences.isPendingMigrationMove()
    preferences.clearPendingMigration()

    if (!move) {
      preferences.setDataLocation(pendingTarget)
      return new MigrationApplication("Now using ${pendingTarget}.".toString(), true)
    }

    DataLocationMigrator.MigrationResult migrationResult = DataLocationMigrator.migrate(oldHome, Paths.get(pendingTarget))
    if (migrationResult.success) {
      preferences.setDataLocation(pendingTarget)
    }
    new MigrationApplication(migrationResult.message, migrationResult.success)
  }

  private static Path effectiveHome(String dataLocation) {
    dataLocation ? Paths.get(dataLocation) : AppPaths.applicationHome()
  }

  @TupleConstructor
  private static final class MigrationApplication {

    final String note
    final boolean success
  }

  @TupleConstructor
  static final class Outcome {

    final String location
    final boolean reachable
    final String reachabilityError
    final String migrationNote
    final boolean migrationFailed

    static Outcome resolved(String location, String migrationNote, boolean migrationFailed) {
      new Outcome(location, true, null, migrationNote, migrationFailed)
    }

    static Outcome unreachable(String location, String reason, String migrationNote, boolean migrationFailed) {
      new Outcome(location, false, reason, migrationNote, migrationFailed)
    }
  }
}
