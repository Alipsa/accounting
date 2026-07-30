package se.alipsa.accounting.support

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Resolves application directories in a platform-aware way.
 */
final class AppPaths {

  private static final Logger log = Logger.getLogger(AppPaths.name)

  static final String HOME_OVERRIDE_PROPERTY = 'alipsa.accounting.home'
  static final String AI_WORKSPACE_HOME_OVERRIDE_PROPERTY = 'alipsa.accounting.aiWorkspace.home'
  static final String DATABASE_URL_PROPERTY = 'alipsa.accounting.db.url'
  // Test-only escape hatch: never set by the shipped application, only by the test suite,
  // so DatabaseService.validateDatabaseUrl can allow fast in-memory H2 during tests while
  // still refusing it for the real desktop app (see CLAUDE.md "H2 is embedded-only").
  static final String ALLOW_IN_MEMORY_DATABASE_PROPERTY = 'alipsa.accounting.test.allowInMemoryDb'
  private static final String AI_WORKSPACE_DIRECTORY_NAME = 'alipsa-accounting-ai-assistant'
  private static final String LEGACY_AI_WORKSPACE_DIRECTORY_NAME = 'ai-workspace'

  private AppPaths() {
  }

  static Path applicationHome() {
    String override = System.getProperty(HOME_OVERRIDE_PROPERTY, '').trim()
    if (override) {
      return Paths.get(override).toAbsolutePath().normalize()
    }
    osDefaultApplicationHome()
  }

  private static Path osDefaultApplicationHome() {
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

  /** A fixed per-user workspace, independent of the configurable data home. */
  static Path aiWorkspaceDirectory() {
    aiWorkspaceHome().resolve(AI_WORKSPACE_DIRECTORY_NAME)
  }

  static void ensureAiWorkspaceHome() {
    Path home = aiWorkspaceHome()
    if (Files.isSymbolicLink(home)) {
      throw new IllegalStateException("Refusing to create the AI workspace below symlinked home ${home}.")
    }
    Files.createDirectories(home)
    if (Files.isSymbolicLink(home) || !Files.isDirectory(home)) {
      throw new IllegalStateException("AI workspace home ${home} is not a real directory.")
    }
    migrateLegacyAiWorkspace(home)
  }

  private static void migrateLegacyAiWorkspace(Path home) {
    Path workspace = aiWorkspaceDirectory()
    Path legacyWorkspace = home.resolve(LEGACY_AI_WORKSPACE_DIRECTORY_NAME)
    if (Files.exists(workspace, LinkOption.NOFOLLOW_LINKS) || !Files.exists(legacyWorkspace, LinkOption.NOFOLLOW_LINKS)) {
      return
    }
    if (Files.isSymbolicLink(legacyWorkspace) || !Files.isDirectory(legacyWorkspace, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalStateException("Legacy AI workspace ${legacyWorkspace} is not a real directory.")
    }
    try {
      Files.move(legacyWorkspace, workspace, StandardCopyOption.ATOMIC_MOVE)
    } catch (AtomicMoveNotSupportedException ignored) {
      Files.move(legacyWorkspace, workspace)
    }
  }

  private static Path aiWorkspaceHome() {
    String override = System.getProperty(AI_WORKSPACE_HOME_OVERRIDE_PROPERTY, '').trim()
    override ? Paths.get(override).toAbsolutePath().normalize() : osDefaultApplicationHome()
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

  static Path sieExportsDirectory() {
    sieExportsDirectory(applicationHome())
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

  static Path sieExportsDirectory(Path applicationHome) {
    applicationHome.resolve('sie-exports')
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
    // Only tighten permissions on the OS-default, single-user local install location. A
    // custom location (a shared/mounted drive the user configured for multi-machine use, or
    // a --home= override) must not be forced to owner-only, or the whole point of sharing it
    // is defeated - and on some network filesystems this can lock other machines out entirely.
    boolean isDefaultLocation = isOsDefaultApplicationHome(applicationHome)
    [
        applicationHome,
        dataDirectory(applicationHome),
        logDirectory(applicationHome),
        attachmentsDirectory(applicationHome),
        reportsDirectory(applicationHome),
        sieExportsDirectory(applicationHome),
        backupsDirectory(applicationHome),
        docsDirectory(applicationHome)
    ].each { Path path ->
      Files.createDirectories(path)
      if (isDefaultLocation) {
        tightenPermissions(path)
      }
    }
  }

  private static boolean isOsDefaultApplicationHome(Path applicationHome) {
    applicationHome.toAbsolutePath().normalize() == osDefaultApplicationHome().toAbsolutePath().normalize()
  }

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
      // Fall back to basic file permissions for non-POSIX file systems.
    } catch (IOException | SecurityException exception) {
      log.log(Level.WARNING, "Kunde inte sätta POSIX-rättigheter på ${path}", exception)
    }

    File directory = path.toFile()
    boolean ok = directory.setReadable(false, false)
    ok &= directory.setWritable(false, false)
    ok &= directory.setExecutable(false, false)
    ok &= directory.setReadable(true, true)
    ok &= directory.setWritable(true, true)
    ok &= directory.setExecutable(true, true)
    if (!ok) {
      log.warning("Kunde inte begränsa rättigheter för ${path}")
    }
  }
}
