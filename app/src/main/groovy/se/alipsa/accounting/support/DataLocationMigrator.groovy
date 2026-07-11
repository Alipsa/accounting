package se.alipsa.accounting.support

import groovy.transform.TupleConstructor

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Validates and performs moves of the application data folder to a new
 * (possibly network-mounted) location, without any Swing or database
 * dependency so it can run before the database is initialized.
 */
final class DataLocationMigrator {

  private DataLocationMigrator() {
  }

  static ValidationResult validateTarget(Path target) {
    if (target == null) {
      return ValidationResult.invalid('No location specified.')
    }
    if (Files.exists(target)) {
      if (!Files.isDirectory(target)) {
        return ValidationResult.invalid("${target} exists but is not a directory.".toString())
      }
      if (!Files.isWritable(target)) {
        return ValidationResult.invalid("${target} is not writable.".toString())
      }
      return ValidationResult.valid()
    }
    Path parent = target.parent
    if (parent == null || !Files.isDirectory(parent) || !Files.isWritable(parent)) {
      return ValidationResult.invalid(
          "${target} does not exist and its parent directory is not accessible.".toString()
      )
    }
    ValidationResult.valid()
  }

  /**
   * Unlike {@link #validateTarget}, a non-existent path here means "unreachable" (e.g. an
   * unmounted network share) rather than "safe to create a new location" - used to check
   * whether an already-configured location can still be used.
   */
  static ValidationResult validateExistingLocation(Path location) {
    if (location == null) {
      return ValidationResult.invalid('No location specified.')
    }
    if (!Files.isDirectory(location)) {
      return ValidationResult.invalid(
          "${location} is not accessible. Make sure the drive or share is connected.".toString()
      )
    }
    if (!Files.isReadable(location) || !Files.isWritable(location)) {
      return ValidationResult.invalid("${location} exists but is not readable/writable.".toString())
    }
    ValidationResult.valid()
  }

  static boolean looksLikeExistingData(Path target) {
    Path databaseFile = accountingDatabaseFile(target)
    Files.exists(databaseFile)
  }

  static MigrationResult migrate(Path source, Path target) {
    ValidationResult sourceValidation = validateExistingLocation(source)
    if (!sourceValidation.valid) {
      return MigrationResult.failure(
          "Source location is not accessible, cannot verify it is safe to move from there: ${sourceValidation.reason}".toString()
      )
    }
    ValidationResult validation = validateTarget(target)
    if (!validation.valid) {
      return MigrationResult.failure(validation.reason)
    }
    boolean targetPreexisted = Files.exists(target)
    try {
      Files.createDirectories(target)
      copyTree(source, target)
    } catch (IOException exception) {
      cleanUpFreshlyCreatedTarget(target, targetPreexisted)
      return MigrationResult.failure("Failed to copy data to ${target}: ${exception.message}".toString())
    }
    if (Files.exists(accountingDatabaseFile(source)) && !Files.exists(accountingDatabaseFile(target))) {
      cleanUpFreshlyCreatedTarget(target, targetPreexisted)
      return MigrationResult.failure("Copy completed but ${accountingDatabaseFile(target)} is missing.".toString())
    }
    MigrationResult.success("Data moved to ${target}.".toString())
  }

  private static void cleanUpFreshlyCreatedTarget(Path target, boolean targetPreexisted) {
    if (targetPreexisted) {
      return
    }
    try {
      Files.walkFileTree(target, new SimpleFileVisitor<Path>() {
        @Override
        FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
          Files.delete(file)
          FileVisitResult.CONTINUE
        }

        @Override
        FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
          Files.delete(directory)
          FileVisitResult.CONTINUE
        }
      })
    } catch (IOException ignored) {
      // Best-effort cleanup; leaving a partial target is safer than throwing here and
      // masking the original migration failure.
    }
  }

  private static Path accountingDatabaseFile(Path applicationHome) {
    Path basePath = AppPaths.databaseBasePath(applicationHome)
    basePath.resolveSibling("${basePath.fileName}.mv.db")
  }

  private static void copyTree(Path source, Path target) throws IOException {
    if (!Files.exists(source)) {
      return
    }
    Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
      @Override
      FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
        Files.createDirectories(target.resolve(source.relativize(directory)))
        FileVisitResult.CONTINUE
      }

      @Override
      FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
        Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING)
        FileVisitResult.CONTINUE
      }
    })
  }

  @TupleConstructor
  static final class ValidationResult {

    final boolean valid
    final String reason

    static ValidationResult valid() {
      new ValidationResult(true, null)
    }

    static ValidationResult invalid(String reason) {
      new ValidationResult(false, reason)
    }
  }

  @TupleConstructor
  static final class MigrationResult {

    final boolean success
    final String message

    static MigrationResult success(String message) {
      new MigrationResult(true, message)
    }

    static MigrationResult failure(String message) {
      new MigrationResult(false, message)
    }
  }
}
