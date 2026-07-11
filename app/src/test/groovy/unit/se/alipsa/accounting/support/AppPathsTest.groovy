package se.alipsa.accounting.support

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assumptions.assumeTrue

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

// This test mutates global system properties (os.name, user.home) to verify
// platform-specific path defaults. Tests must run single-threaded to avoid
// interfering with other tests that call AppPaths.applicationHome() concurrently.
class AppPathsTest {

  @TempDir
  Path tempDir

  private String previousOsName
  private String previousUserHome
  private String previousHomeOverride

  @BeforeEach
  void captureSystemProperties() {
    previousOsName = System.getProperty('os.name')
    previousUserHome = System.getProperty('user.home')
    previousHomeOverride = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
  }

  @AfterEach
  void restoreSystemProperties() {
    restoreProperty('os.name', previousOsName)
    restoreProperty('user.home', previousUserHome)
    restoreProperty(AppPaths.HOME_OVERRIDE_PROPERTY, previousHomeOverride)
  }

  @Test
  void applicationHomeUsesLinuxDefaultPath() {
    System.setProperty('os.name', 'Linux')
    System.setProperty('user.home', '/tmp/alipsa-home')

    assertEquals(Paths.get('/tmp/alipsa-home', '.local', 'share', 'alipsa-accounting'), AppPaths.applicationHome())
  }

  @Test
  void applicationHomeUsesWindowsDefaultPath() {
    System.setProperty('os.name', 'Windows 11')
    System.setProperty('user.home', 'C:/Users/Per')

    String appData = System.getenv('APPDATA') ?: Paths.get('C:/Users/Per', 'AppData', 'Roaming').toString()
    assertEquals(Paths.get(appData, 'Alipsa', 'Accounting'), AppPaths.applicationHome())
  }

  @Test
  void applicationHomeUsesMacosDefaultPath() {
    System.setProperty('os.name', 'Mac OS X')
    System.setProperty('user.home', '/Users/per')

    assertEquals(Paths.get('/Users/per', 'Library', 'Application Support', 'AlipsaAccounting'), AppPaths.applicationHome())
  }

  @Test
  void applicationHomeOverrideTakesPrecedenceForAllDerivedDirectories() {
    Path overrideHome = tempDir.resolve('accounting-home')
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, overrideHome.toString())

    Path home = AppPaths.applicationHome()

    assertEquals(overrideHome.toAbsolutePath().normalize(), home)
    assertEquals(home.resolve('data'), AppPaths.dataDirectory())
    assertEquals(home.resolve('logs'), AppPaths.logDirectory())
    assertEquals(home.resolve('attachments'), AppPaths.attachmentsDirectory())
    assertEquals(home.resolve('reports'), AppPaths.reportsDirectory())
    assertEquals(home.resolve('backups'), AppPaths.backupsDirectory())
    assertEquals(home.resolve('docs'), AppPaths.docsDirectory())
  }

  @Test
  void ensureDirectoryStructurePreservesPermissionsForCustomHome() {
    assumePosixPermissionsSupported()
    Path customHome = tempDir.resolve('shared-home')
    List<Path> directories = [
        customHome,
        AppPaths.dataDirectory(customHome),
        AppPaths.logDirectory(customHome),
        AppPaths.attachmentsDirectory(customHome),
        AppPaths.reportsDirectory(customHome),
        AppPaths.sieExportsDirectory(customHome),
        AppPaths.backupsDirectory(customHome),
        AppPaths.docsDirectory(customHome)
    ]
    Set<PosixFilePermission> sharedPermissions = PosixFilePermissions.fromString('rwxrwx---')
    directories.each { Path directory ->
      Files.createDirectories(directory)
      Files.setPosixFilePermissions(directory, sharedPermissions)
    }

    AppPaths.ensureDirectoryStructure(customHome)

    directories.each { Path directory ->
      assertEquals(sharedPermissions, Files.getPosixFilePermissions(directory))
    }
  }

  @Test
  void ensureDirectoryStructureTightensPermissionsForDefaultHome() {
    assumePosixPermissionsSupported()
    System.setProperty('os.name', 'Linux')
    System.setProperty('user.home', tempDir.toString())
    Path defaultHome = Paths.get(tempDir.toString(), '.local', 'share', 'alipsa-accounting')

    AppPaths.ensureDirectoryStructure(defaultHome)

    Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString('rwx------')
    [
        defaultHome,
        AppPaths.dataDirectory(defaultHome),
        AppPaths.logDirectory(defaultHome),
        AppPaths.attachmentsDirectory(defaultHome),
        AppPaths.reportsDirectory(defaultHome),
        AppPaths.sieExportsDirectory(defaultHome),
        AppPaths.backupsDirectory(defaultHome),
        AppPaths.docsDirectory(defaultHome)
    ].each { Path directory ->
      assertEquals(ownerOnly, Files.getPosixFilePermissions(directory))
    }
  }

  private static void assumePosixPermissionsSupported() {
    assumeTrue(FileSystems.default.supportedFileAttributeViews().contains('posix'))
  }

  private static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name)
      return
    }
    System.setProperty(name, value)
  }
}
