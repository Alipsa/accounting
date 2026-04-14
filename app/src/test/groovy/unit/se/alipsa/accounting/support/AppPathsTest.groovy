package se.alipsa.accounting.support

import static org.junit.jupiter.api.Assertions.assertEquals

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Path
import java.nio.file.Paths

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

  private static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name)
      return
    }
    System.setProperty(name, value)
  }
}
