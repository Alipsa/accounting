package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.*
import static org.junit.jupiter.api.Assumptions.assumeTrue

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

final class UpdateServiceUpdaterScriptTest {

  @TempDir
  Path tempDir

  private String previousOsName

  @BeforeEach
  void captureSystemProperties() {
    previousOsName = System.getProperty('os.name')
  }

  @AfterEach
  void restoreSystemProperties() {
    restoreProperty('os.name', previousOsName)
  }

  @ParameterizedTest
  @ValueSource(strings = ['Linux', 'Mac OS X'])
  void generatedUnixUpdaterScriptWritesPersistentLog(String osName) {
    ScriptFixture fixture = createFixture()
    System.setProperty('os.name', osName)

    Path script = new UpdateService().writeUpdaterScript(
        fixture.stagingDir, fixture.extractedDir, fixture.installDir, fixture.updaterLog)
    String content = Files.readString(script)

    assertEquals('updater.sh', script.fileName.toString())
    if (File.separatorChar == (char) '/') {
      assertTrue(Files.isExecutable(script))
    }
    assertTrue(content.contains("LOG_FILE=\"${fixture.updaterLog}\""))
    assertTrue(content.contains('exec >> "$LOG_FILE" 2>&1'))
    assertTrue(content.contains('log "Starting update."'))
    assertTrue(content.contains('log "Backing up current JAR files."'))
    assertTrue(content.contains('log "Copying updated JAR files."'))
    assertTrue(content.contains('log "Update failed while copying files, restoring backup..."'))
    assertTrue(content.contains('log "Updating launcher configuration."'))
    assertTrue(content.contains('log "Launching application."'))
    assertTrue(content.contains('app-1.4.0.jar'))
  }

  @Test
  void generatedWindowsUpdaterScriptWritesPersistentLog() {
    ScriptFixture fixture = createFixture()
    System.setProperty('os.name', 'Windows 11')

    Path script = new UpdateService().writeUpdaterScript(
        fixture.stagingDir, fixture.extractedDir, fixture.installDir, fixture.updaterLog)
    String content = Files.readString(script)

    assertEquals('updater.bat', script.fileName.toString())
    assertTrue(content.contains("set \"LOG_FILE=${fixture.updaterLog}\""))
    assertTrue(content.contains('call :main >> "%LOG_FILE%" 2>&1'))
    assertTrue(content.contains('echo [%DATE% %TIME%] Starting update.'))
    assertTrue(content.contains('echo [%DATE% %TIME%] Backing up current JAR files.'))
    assertTrue(content.contains('echo [%DATE% %TIME%] Copying updated JAR files.'))
    assertTrue(content.contains('Update failed while copying files, restoring backup'))
    assertTrue(content.contains('echo [%DATE% %TIME%] Updating launcher configuration.'))
    assertTrue(content.contains('echo [%DATE% %TIME%] Launching application.'))
   assertTrue(content.contains('app-1.4.0.jar'))
   assertTrue(content.contains("'app.classpath=\$APPDIR/app-1.4.0.jar'"))
    assertTrue(content.contains("\$_.StartsWith('app.classpath=')"))
   assertFalse(content.contains('del "%~f0"'))
  }

  @Test
  void unixUpdaterScriptUpdatesFakeInstallDirectoryAndLogsProgress() {
    assumeTrue(File.separatorChar == (char) '/', 'Unix updater execution is only verified on Unix-like hosts.')
    assumeTrue(commandAvailable('bash'), 'bash is required to execute the generated updater script.')

    ScriptFixture fixture = createFixture()
    System.setProperty('os.name', 'Linux')
    Path script = new UpdateService().writeUpdaterScript(
        fixture.stagingDir, fixture.extractedDir, fixture.installDir, fixture.updaterLog)
    restoreProperty('os.name', previousOsName)

    Process process = new ProcessBuilder('bash', script.toString())
        .directory(fixture.stagingDir.toFile())
        .start()
    boolean finished = process.waitFor(10, TimeUnit.SECONDS)
    if (!finished) {
      process.destroyForcibly()
    }

    assertTrue(finished, 'Updater script did not finish within the timeout.')
    assertEquals(0, process.exitValue())
    assertFalse(Files.exists(fixture.installDir.resolve('app-1.2.0.jar')))
    assertTrue(Files.isRegularFile(fixture.installDir.resolve('app-1.4.0.jar')))
    assertFalse(Files.exists(script))

    String cfg = Files.readString(fixture.installDir.resolve('app.cfg'))
    assertTrue(cfg.contains('app.classpath=$APPDIR/app-1.4.0.jar'))
    assertTrue(cfg.contains('java-options=-Djpackage.app-version=1.4.0'))

    String log = Files.readString(fixture.updaterLog)
    assertTrue(log.contains('Starting update.'))
    assertTrue(log.contains('Backing up current JAR files.'))
    assertTrue(log.contains('Copying updated JAR files.'))
    assertTrue(log.contains('Updating launcher configuration.'))
    assertTrue(log.contains('Update script finished.'))
  }

  private ScriptFixture createFixture() {
    Path stagingDir = tempDir.resolve('staging')
    Path extractedDir = stagingDir.resolve('extracted')
    Path installDir = tempDir.resolve('install').resolve('lib').resolve('app')
    Path updaterLog = tempDir.resolve('logs').resolve('updater.log')

    Files.createDirectories(extractedDir)
    Files.createDirectories(installDir)
    Files.createDirectories(updaterLog.parent)
    Files.writeString(extractedDir.resolve('app-1.4.0.jar'), 'new jar')
    Files.writeString(installDir.resolve('app-1.2.0.jar'), 'old jar')
    Files.writeString(installDir.resolve('app.cfg'),
        'app.classpath=$APPDIR/app-1.2.0.jar\njava-options=-Djpackage.app-version=1.2.0\n')

    new ScriptFixture(stagingDir, extractedDir, installDir, updaterLog)
  }

  private static boolean commandAvailable(String command) {
    try {
      Process process = new ProcessBuilder(command, '--version').start()
      boolean finished = process.waitFor(5, TimeUnit.SECONDS)
      return finished && process.exitValue() == 0
    } catch (IOException ignored) {
      return false
    }
  }

  private static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name)
      return
    }
    System.setProperty(name, value)
  }

  private static final class ScriptFixture {

    final Path stagingDir
    final Path extractedDir
    final Path installDir
    final Path updaterLog

    private ScriptFixture(Path stagingDir, Path extractedDir, Path installDir, Path updaterLog) {
      this.stagingDir = stagingDir
      this.extractedDir = extractedDir
      this.installDir = installDir
      this.updaterLog = updaterLog
    }
  }
}
