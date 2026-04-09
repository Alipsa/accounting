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
    applicationHome().resolve('data')
  }

  static Path logDirectory() {
    applicationHome().resolve('logs')
  }

  static Path databaseBasePath() {
    dataDirectory().resolve('accounting')
  }

  static void ensureDirectoryStructure() {
    [applicationHome(), dataDirectory(), logDirectory()].each { Path path ->
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
