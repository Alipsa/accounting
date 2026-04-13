package se.alipsa.accounting.support

import groovy.transform.CompileStatic

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission

/**
 * Resolves application directories in a platform-aware way.
 */
@CompileStatic
final class AppPaths {

  static final String HOME_OVERRIDE_PROPERTY = 'alipsa.accounting.home'
  static final String DATABASE_URL_PROPERTY = 'alipsa.accounting.db.url'

  private AppPaths() {
  }

  static Path applicationHome() {
    String override = System.getProperty(HOME_OVERRIDE_PROPERTY, '').trim()
    if (override) {
      return Paths.get(override).toAbsolutePath().normalize()
    }

    String osName = System.getProperty('os.name', 'unknown').toLowerCase(Locale.ROOT)
    String userHome = System.getProperty('user.home')
    if (osName.contains('win')) {
      String appData = System.getenv('APPDATA') ?: Paths.get(userHome, 'AppData', 'Roaming').toString()
      return Paths.get(appData, 'Alipsa', 'Accounting')
    }
    if (osName.contains('mac')) {
      return Paths.get(userHome, 'Library', 'Application Support', 'AlipsaAccounting')
    }
    Paths.get(userHome, '.local', 'share', 'alipsa-accounting')
  }

  static Path dataDirectory() {
    dataDirectory(applicationHome())
  }

  static Path logDirectory() {
    logDirectory(applicationHome())
  }

  static Path attachmentsDirectory() {
    attachmentsDirectory(applicationHome())
  }

  static Path reportsDirectory() {
    reportsDirectory(applicationHome())
  }

  static Path backupsDirectory() {
    backupsDirectory(applicationHome())
  }

  static Path docsDirectory() {
    docsDirectory(applicationHome())
  }

  static Path databaseBasePath() {
    databaseBasePath(applicationHome())
  }

  static Path dataDirectory(Path applicationHome) {
    applicationHome.resolve('data')
  }

  static Path logDirectory(Path applicationHome) {
    applicationHome.resolve('logs')
  }

  static Path attachmentsDirectory(Path applicationHome) {
    applicationHome.resolve('attachments')
  }

  static Path reportsDirectory(Path applicationHome) {
    applicationHome.resolve('reports')
  }

  static Path backupsDirectory(Path applicationHome) {
    applicationHome.resolve('backups')
  }

  static Path docsDirectory(Path applicationHome) {
    applicationHome.resolve('docs')
  }

  static Path databaseBasePath(Path applicationHome) {
    dataDirectory(applicationHome).resolve('accounting')
  }

  static void ensureDirectoryStructure() {
    ensureDirectoryStructure(applicationHome())
  }

  static void ensureDirectoryStructure(Path applicationHome) {
    [
        applicationHome,
        dataDirectory(applicationHome),
        logDirectory(applicationHome),
        attachmentsDirectory(applicationHome),
        reportsDirectory(applicationHome),
        backupsDirectory(applicationHome),
        docsDirectory(applicationHome)
    ].each { Path path ->
      Files.createDirectories(path)
      tightenPermissions(path)
    }
  }

  @SuppressWarnings('CatchException')
  private static void tightenPermissions(Path path) {
    try {
      Files.setPosixFilePermissions(
          path,
          [
              PosixFilePermission.OWNER_READ,
              PosixFilePermission.OWNER_WRITE,
              PosixFilePermission.OWNER_EXECUTE
          ] as Set<PosixFilePermission>
      )
      return
    } catch (UnsupportedOperationException ignored) {
      // Ignore and fall back to basic file permissions for non-POSIX file systems.
    } catch (Exception ignored) {
      // Ignore and fall back to basic file permissions if the filesystem rejects POSIX attrs.
    }

    File directory = path.toFile()
    directory.setReadable(false, false)
    directory.setWritable(false, false)
    directory.setExecutable(false, false)
    directory.setReadable(true, true)
    directory.setWritable(true, true)
    directory.setExecutable(true, true)
  }
}
