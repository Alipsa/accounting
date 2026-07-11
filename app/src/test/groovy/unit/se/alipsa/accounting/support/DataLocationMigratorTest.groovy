package unit.se.alipsa.accounting.support

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.support.AppPaths
import se.alipsa.accounting.support.DataLocationMigrator

import java.nio.file.Files
import java.nio.file.Path

class DataLocationMigratorTest {

  @TempDir
  Path tempDir

  @Test
  void validateTargetAcceptsWritableExistingDirectory() {
    Path target = Files.createDirectories(tempDir.resolve('existing'))

    DataLocationMigrator.ValidationResult result = DataLocationMigrator.validateTarget(target)

    assertTrue(result.valid)
  }

  @Test
  void validateTargetAcceptsNonExistentDirectoryWithAccessibleParent() {
    Path target = tempDir.resolve('not-yet-created')

    DataLocationMigrator.ValidationResult result = DataLocationMigrator.validateTarget(target)

    assertTrue(result.valid)
  }

  @Test
  void validateTargetRejectsPathThatIsAFile() {
    Path target = tempDir.resolve('a-file')
    Files.createFile(target)

    DataLocationMigrator.ValidationResult result = DataLocationMigrator.validateTarget(target)

    assertFalse(result.valid)
  }

  @Test
  void validateTargetRejectsPathWithMissingParent() {
    Path target = tempDir.resolve('missing-parent').resolve('nested')

    DataLocationMigrator.ValidationResult result = DataLocationMigrator.validateTarget(target)

    assertFalse(result.valid)
  }

  @Test
  void validateExistingLocationAcceptsAccessibleDirectory() {
    Path location = Files.createDirectories(tempDir.resolve('existing-data-dir'))

    DataLocationMigrator.ValidationResult result = DataLocationMigrator.validateExistingLocation(location)

    assertTrue(result.valid)
  }

  @Test
  void validateExistingLocationRejectsNonExistentPathEvenWithAccessibleParent() {
    // Unlike validateTarget(), a non-existent path here means "unreachable" (e.g. an
    // unmounted network share), not "safe to create a new location".
    Path location = tempDir.resolve('unmounted-share')

    DataLocationMigrator.ValidationResult result = DataLocationMigrator.validateExistingLocation(location)

    assertFalse(result.valid)
  }

  @Test
  void validateExistingLocationRejectsPathThatIsAFile() {
    Path location = tempDir.resolve('existing-data-file')
    Files.createFile(location)

    DataLocationMigrator.ValidationResult result = DataLocationMigrator.validateExistingLocation(location)

    assertFalse(result.valid)
  }

  @Test
  void looksLikeExistingDataIsFalseForEmptyDirectory() {
    Path target = Files.createDirectories(tempDir.resolve('empty'))

    assertFalse(DataLocationMigrator.looksLikeExistingData(target))
  }

  @Test
  void looksLikeExistingDataIsTrueWhenAccountingDatabaseFilePresent() {
    Path target = Files.createDirectories(tempDir.resolve('populated'))
    Path dataDir = Files.createDirectories(AppPaths.dataDirectory(target))
    Files.createFile(dataDir.resolve('accounting.mv.db'))

    assertTrue(DataLocationMigrator.looksLikeExistingData(target))
  }

  @Test
  void migrateCopiesEntireApplicationHomeTreeToTarget() {
    Path source = Files.createDirectories(tempDir.resolve('source'))
    Path dataDir = Files.createDirectories(AppPaths.dataDirectory(source))
    Files.write(dataDir.resolve('accounting.mv.db'), 'db-bytes'.bytes)
    Path attachmentsDir = Files.createDirectories(AppPaths.attachmentsDirectory(source))
    Files.write(attachmentsDir.resolve('receipt.pdf'), 'pdf-bytes'.bytes)
    Path target = tempDir.resolve('target')

    DataLocationMigrator.MigrationResult result = DataLocationMigrator.migrate(source, target)

    assertTrue(result.success)
    assertEquals('db-bytes', new String(Files.readAllBytes(AppPaths.dataDirectory(target).resolve('accounting.mv.db'))))
    assertEquals('pdf-bytes', new String(Files.readAllBytes(AppPaths.attachmentsDirectory(target).resolve('receipt.pdf'))))
  }

  @Test
  void migrateFailsWithoutModifyingSourceWhenTargetIsInvalid() {
    Path source = Files.createDirectories(tempDir.resolve('source2'))
    Path sourceDataDir = Files.createDirectories(AppPaths.dataDirectory(source))
    Files.write(sourceDataDir.resolve('accounting.mv.db'), 'db-bytes'.bytes)
    Path invalidTarget = tempDir.resolve('a-file-target')
    Files.createFile(invalidTarget)

    DataLocationMigrator.MigrationResult result = DataLocationMigrator.migrate(source, invalidTarget)

    assertFalse(result.success)
    assertTrue(Files.exists(AppPaths.dataDirectory(source).resolve('accounting.mv.db')))
  }

  @Test
  void migrateFailsWithoutCreatingTargetWhenSourceDoesNotExist() {
    Path source = tempDir.resolve('never-mounted-source')
    Path target = tempDir.resolve('target-for-missing-source')

    DataLocationMigrator.MigrationResult result = DataLocationMigrator.migrate(source, target)

    assertFalse(result.success)
    assertFalse(Files.exists(target))
  }

  @Test
  void migrateFailsWhenSourceExistsButIsAFile() {
    Path source = tempDir.resolve('source-is-a-file')
    Files.createFile(source)
    Path target = tempDir.resolve('target-for-file-source')

    DataLocationMigrator.MigrationResult result = DataLocationMigrator.migrate(source, target)

    assertFalse(result.success)
    assertFalse(Files.exists(target))
  }

  @Test
  void migrateFailsWhenTargetIsSameAsSource() {
    Path source = Files.createDirectories(tempDir.resolve('same-dir'))
    Files.createDirectories(AppPaths.dataDirectory(source))
    Files.write(AppPaths.dataDirectory(source).resolve('accounting.mv.db'), 'db-bytes'.bytes)

    DataLocationMigrator.MigrationResult result = DataLocationMigrator.migrate(source, source)

    assertFalse(result.success)
  }

  @Test
  void migrateFailsWhenTargetIsNestedInsideSource() {
    Path source = Files.createDirectories(tempDir.resolve('parent-dir'))
    Files.createDirectories(AppPaths.dataDirectory(source))
    Files.write(AppPaths.dataDirectory(source).resolve('accounting.mv.db'), 'db-bytes'.bytes)
    Path target = source.resolve('nested-target')

    DataLocationMigrator.MigrationResult result = DataLocationMigrator.migrate(source, target)

    assertFalse(result.success)
    assertFalse(Files.exists(target))
  }

  @Test
  void migrateFailsWithoutOverwritingWhenTargetAlreadyHasAccountingData() {
    Path source = Files.createDirectories(tempDir.resolve('source-with-data'))
    Files.createDirectories(AppPaths.dataDirectory(source))
    Files.write(AppPaths.dataDirectory(source).resolve('accounting.mv.db'), 'source-db-bytes'.bytes)
    Path target = Files.createDirectories(tempDir.resolve('already-populated-target'))
    Files.createDirectories(AppPaths.dataDirectory(target))
    Files.write(AppPaths.dataDirectory(target).resolve('accounting.mv.db'), 'target-db-bytes'.bytes)

    DataLocationMigrator.MigrationResult result = DataLocationMigrator.migrate(source, target)

    assertFalse(result.success)
    assertEquals('target-db-bytes', new String(Files.readAllBytes(AppPaths.dataDirectory(target).resolve('accounting.mv.db'))))
  }
}
