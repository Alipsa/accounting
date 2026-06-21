package se.alipsa.accounting.service

import groovy.json.JsonSlurper
import groovy.transform.CompileDynamic

import se.alipsa.accounting.support.AppPaths
import se.alipsa.accounting.support.LoggingConfigurer

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.logging.Logger
import java.util.regex.Matcher
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Checks for application updates via the GitHub releases API,
 * downloads the platform-independent distribution, and stages
 * a restart with the updated JARs.
 */
final class UpdateService {

  private static final Logger log = Logger.getLogger(UpdateService.name)
  private static final String REPO = 'Alipsa/accounting'
  private static final String LATEST_RELEASE_URL = "https://api.github.com/repos/${REPO}/releases/latest"
  private static final String DIST_ASSET_PREFIX = 'app-'

  String currentVersion() {
    UpdateService.package?.implementationVersion ?: 'dev'
  }

  @CompileDynamic
  UpdateInfo checkForUpdate() {
    HttpClient client = HttpClient.newHttpClient()
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(LATEST_RELEASE_URL))
        .header('Accept', 'application/vnd.github+json')
        .header('User-Agent', "AlipsaAccounting/${currentVersion()}")
        .GET()
        .build()
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() != 200) {
      throw new IOException("GitHub API returned ${response.statusCode()}")
    }

    Map<String, Object> release = new JsonSlurper().parseText(response.body()) as Map<String, Object>
    String tagName = release.get('tag_name') as String
    String remoteVersion = tagName?.startsWith('v') ? tagName.substring(1) : tagName
    String releaseNotes = release.get('body') as String ?: ''
    String htmlUrl = release.get('html_url') as String ?: ''

    String downloadUrl = null
    String checksumUrl = null
    List<Map<String, Object>> assets = release.get('assets') as List<Map<String, Object>>
    if (assets != null) {
      for (Map<String, Object> asset : assets) {
        String name = asset.get('name') as String
        if (name != null && name.startsWith(DIST_ASSET_PREFIX) && name.endsWith('.zip')) {
          downloadUrl = asset.get('browser_download_url') as String
        }
        if (name != null && name.startsWith(DIST_ASSET_PREFIX) && name.endsWith('.zip.sha256')) {
          checksumUrl = asset.get('browser_download_url') as String
        }
      }
    }

    new UpdateInfo(
        currentVersion: currentVersion(),
        availableVersion: remoteVersion,
        downloadUrl: downloadUrl,
        checksumUrl: checksumUrl,
        releaseNotes: releaseNotes,
        releasePageUrl: htmlUrl,
        updateAvailable: isNewer(remoteVersion, currentVersion())
    )
  }

  Path downloadUpdate(UpdateInfo info, Closure<Void> progressCallback) {
    if (info.downloadUrl == null) {
      throw new IllegalStateException('No download URL available for the update.')
    }

    log.info("Downloading update ${info.availableVersion} from ${info.downloadUrl}")
    Path stagingDir = stagingDirectory()
    Files.createDirectories(stagingDir)
    Path targetFile = stagingDir.resolve("app-${info.availableVersion}.zip")

    HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(info.downloadUrl))
        .header('User-Agent', "AlipsaAccounting/${currentVersion()}")
        .GET()
        .build()
    HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
    if (response.statusCode() != 200) {
      throw new IOException("Download failed with status ${response.statusCode()}")
    }

    long contentLength = response.headers().firstValueAsLong('content-length').orElse(-1L)
    response.body().withCloseable { InputStream input ->
      new FileOutputStream(targetFile.toFile()).withCloseable { FileOutputStream output ->
        byte[] buffer = new byte[8192]
        long totalRead = 0
        int bytesRead
        while ((bytesRead = input.read(buffer)) != -1) {
          output.write(buffer, 0, bytesRead)
          totalRead += bytesRead
          if (contentLength > 0 && progressCallback != null) {
            progressCallback.call((int) ((totalRead * 100) / contentLength))
          }
        }
      }
    }

    if (info.checksumUrl != null) {
      log.info("Verifying checksum for ${targetFile}")
      verifyChecksum(targetFile, info.checksumUrl, client)
    }

    log.info("Downloaded update archive to ${targetFile}")
    targetFile
  }

  private void verifyChecksum(Path file, String checksumUrl, HttpClient client) {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(checksumUrl))
        .header('User-Agent', "AlipsaAccounting/${currentVersion()}")
        .GET()
        .build()
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() != 200) {
      throw new IOException("Checksum download failed with status ${response.statusCode()}")
    }

    String expectedHash = response.body().trim().split('\\s+')[0]
    String actualHash = sha256(file)
    if (expectedHash != actualHash) {
      Files.deleteIfExists(file)
      throw new IOException("Checksum mismatch: expected ${expectedHash} but got ${actualHash}")
    }
  }

  private static String sha256(Path file) {
    MessageDigest digest = MessageDigest.getInstance('SHA-256')
    Files.newInputStream(file).withCloseable { InputStream input ->
      byte[] buffer = new byte[8192]
      int bytesRead
      while ((bytesRead = input.read(buffer)) != -1) {
        digest.update(buffer, 0, bytesRead)
      }
    }
    digest.digest().collect { String.format('%02x', it) }.join()
  }

  void applyUpdateAndRestart(Path downloadedZip) {
    applyUpdateAndRestart(downloadedZip, null)
  }

  void applyUpdateAndRestart(Path downloadedZip, Closure<Void> phaseCallback) {
    log.info("Preparing to apply update from ${downloadedZip}")
    Path stagingDir = stagingDirectory()
    Path extractedDir = stagingDir.resolve('extracted')
    notifyApplyPhase(phaseCallback, ApplyPhase.EXTRACTING)
    log.info("Extracting update archive to ${extractedDir}")
    deleteDirectoryContents(extractedDir)
    Files.createDirectories(extractedDir)

    extractJars(downloadedZip, extractedDir)

    notifyApplyPhase(phaseCallback, ApplyPhase.STAGING)
    log.info('Staging updater script.')
    Path installDir = installationJarDirectory()
    if (installDir == null) {
      throw new IllegalStateException('Cannot determine installation JAR directory.')
    }
    Path updaterLog = updateLogPath()
    Files.createDirectories(updaterLog.parent)

    Path updaterScript = writeUpdaterScript(stagingDir, extractedDir, installDir, updaterLog)
    log.info("Updater script written to ${updaterScript}; updater log will be ${updaterLog}")
    notifyApplyPhase(phaseCallback, ApplyPhase.LAUNCHING)
    log.info('Launching updater and exiting application.')
    LoggingConfigurer.shutdown()
    launchUpdaterAndExit(updaterScript)
    throw new IllegalStateException('Updater was launched, but the application did not exit.')
  }

  Path installationJarDirectory() {
    URL codeSource = UpdateService.protectionDomain?.codeSource?.location
    if (codeSource == null) {
      return null
    }
    Path jarPath = Path.of(codeSource.toURI())
    if (!Files.isRegularFile(jarPath)) {
      return null
    }
    jarPath.parent
  }

  Path launcherPath() {
    Path jarDir = installationJarDirectory()
    if (jarDir == null) {
      return null
    }
    launcherPath(jarDir, System.getProperty('os.name', '').toLowerCase(Locale.ROOT))
  }

  Path updateLogPath() {
    AppPaths.logDirectory().resolve('updater.log')
  }

  static Path launcherPath(Path jarDir, String osName) {
    if (osName.contains('win')) {
      return appImageRoot(jarDir).resolve('AlipsaAccounting.exe')
    }
    if (osName.contains('mac')) {
      return appImageRoot(jarDir).resolve('MacOS').resolve('AlipsaAccounting')
    }
    appImageRoot(jarDir).resolve('bin').resolve('AlipsaAccounting')
  }

  static Path appImageRoot(Path jarDir) {
    if (jarDir?.fileName?.toString() == 'app' && jarDir.parent?.fileName?.toString() == 'lib') {
      return jarDir.parent.parent
    }
    jarDir.parent
  }

  private static Path stagingDirectory() {
    AppPaths.applicationHome().resolve('updates')
  }

  private static void extractJars(Path zipFile, Path targetDir) {
    new ZipInputStream(new FileInputStream(zipFile.toFile())).withCloseable { ZipInputStream zis ->
      ZipEntry entry
      while ((entry = zis.nextEntry) != null) {
        if (entry.directory || !entry.name.endsWith('.jar')) {
          continue
        }
        String fileName = Path.of(entry.name).fileName
        Path outputFile = targetDir.resolve(fileName)
        Files.copy(zis, outputFile, StandardCopyOption.REPLACE_EXISTING)
      }
    }
  }

  Path writeUpdaterScript(Path stagingDir, Path extractedDir, Path installDir, Path updaterLog) {
    String osName = System.getProperty('os.name', '').toLowerCase(Locale.ROOT)
    boolean isWindows = osName.contains('win')
    Path launcher = launcherPath()
    String launcherCommand = launcher != null ? "\"${launcher}\"" : ''
    Path backupDir = installDir.resolve('.update-backup')
    String newMainJar = mainJarFileName(extractedDir) ?: ''
    String newVersion = versionFromMainJar(newMainJar) ?: ''
    UpdaterScriptContext context = new UpdaterScriptContext(
        stagingDir,
        extractedDir,
        installDir,
        updaterLog,
        backupDir,
        launcherCommand,
        newMainJar,
        newVersion
    )

    Path script
    if (isWindows) {
      script = stagingDir.resolve('updater.bat')
      script.toFile().text = windowsUpdaterScript(context)
    } else {
      script = stagingDir.resolve('updater.sh')
      script.toFile().text = unixUpdaterScript(context)
      script.toFile().setExecutable(true)
    }
    script
  }

  private static String windowsUpdaterScript(UpdaterScriptContext context) {
    """\
@echo off
set "LOG_FILE=${context.updaterLog}"
call :main >> "%LOG_FILE%" 2>&1
exit /b %ERRORLEVEL%

:main
echo [%DATE% %TIME%] Starting update.
echo [%DATE% %TIME%] Install dir: ${context.installDir}
echo [%DATE% %TIME%] Extracted dir: ${context.extractedDir}
timeout /t 3 /nobreak >nul
echo [%DATE% %TIME%] Preparing backup directory: ${context.backupDir}
if exist "${context.backupDir}" rd /s /q "${context.backupDir}"
mkdir "${context.backupDir}"
if errorlevel 1 (
  echo [%DATE% %TIME%] Failed to create backup directory.
  exit /b 1
)
echo [%DATE% %TIME%] Backing up current JAR files.
for %%f in ("${context.installDir}\\*.jar") do move "%%f" "${context.backupDir}\\"
if errorlevel 1 (
  echo [%DATE% %TIME%] Failed to back up current JAR files.
  exit /b 1
)
echo [%DATE% %TIME%] Copying updated JAR files.
for %%f in ("${context.extractedDir}\\*.jar") do copy /y "%%f" "${context.installDir}\\"
if errorlevel 1 (
  echo [%DATE% %TIME%] Update failed while copying files, restoring backup...
  for %%f in ("${context.backupDir}\\*.jar") do move "%%f" "${context.installDir}\\"
  rd /s /q "${context.backupDir}"
  echo [%DATE% %TIME%] Update failed. Please try again.
  pause
  exit /b 1
)
echo [%DATE% %TIME%] Updating launcher configuration.
${context.newMainJar.isEmpty() ? '' : windowsConfigUpdateCommand(context.installDir, context.newMainJar, context.newVersion)}
echo [%DATE% %TIME%] Cleaning update staging files.
rd /s /q "${context.backupDir}"
rd /s /q "${context.extractedDir}"
del "${context.stagingDir}\\*.zip"
echo [%DATE% %TIME%] Launching application.
${context.launcherCommand.isEmpty() ? 'echo Update complete.' : "start \"\" ${context.launcherCommand}"}
echo [%DATE% %TIME%] Update script finished.
del "%~f0"
""".stripIndent()
  }

  private static String unixUpdaterScript(UpdaterScriptContext context) {
    """\
#!/usr/bin/env bash
LOG_FILE="${context.updaterLog}"
exec >> "\$LOG_FILE" 2>&1

timestamp() {
  date -Is
}

log() {
  echo "[\$(timestamp)] \$*"
}

fail() {
  log "\$*"
  exit 1
}

log "Starting update."
log "Install dir: ${context.installDir}"
log "Extracted dir: ${context.extractedDir}"
sleep 3
log "Preparing backup directory: ${context.backupDir}"
rm -rf "${context.backupDir}"
mkdir -p "${context.backupDir}" || fail "Failed to create backup directory."
log "Backing up current JAR files."
if ! mv "${context.installDir}/"*.jar "${context.backupDir}/"; then
  fail "Failed to back up current JAR files."
fi
log "Copying updated JAR files."
if ! cp "${context.extractedDir}/"*.jar "${context.installDir}/"; then
  log "Update failed while copying files, restoring backup..."
  mv "${context.backupDir}/"*.jar "${context.installDir}/"
  rm -rf "${context.backupDir}"
  fail "Update failed. Please try again."
fi
log "Updating launcher configuration."
${context.newMainJar.isEmpty() ? '' : unixConfigUpdateCommand(context.installDir, context.newMainJar, context.newVersion)}
log "Cleaning update staging files."
rm -rf "${context.backupDir}"
rm -rf "${context.extractedDir}"
rm -f "${context.stagingDir}/"*.zip
log "Launching application."
${context.launcherCommand.isEmpty() ? 'echo "Update complete."' : "${context.launcherCommand} &"}
log "Update script finished."
rm -f "\$0"
""".stripIndent()
  }

  private static String mainJarFileName(Path directory) {
    if (!Files.isDirectory(directory)) {
      return null
    }
    Files.list(directory).withCloseable { stream ->
      stream
          .map { Path path -> path.fileName.toString() }
          .filter { String fileName -> fileName ==~ /app-\d+(\.\d+)*\.jar/ }
          .sorted()
          .findFirst()
          .orElse(null)
    }
  }

  private static String versionFromMainJar(String fileName) {
    Matcher matcher = fileName =~ /^app-(.+)\.jar$/
    matcher.matches() ? matcher.group(1) : null
  }

  private static String unixConfigUpdateCommand(Path installDir, String newMainJar, String newVersion) {
    String versionCommand = newVersion.isEmpty()
        ? ''
        : "  sed -i.bak \"s#^java-options=-Djpackage.app-version=.*#java-options=-Djpackage.app-version=${newVersion}#\" \"\$cfg\"\n"
    """\
for cfg in "${installDir}/"*.cfg; do
  [ -f "\$cfg" ] || continue
  sed -i.bak "s#^app.classpath=\\\$APPDIR/app-.*\\.jar#app.classpath=\\\$APPDIR/${newMainJar}#" "\$cfg"
${versionCommand}  rm -f "\$cfg.bak"
done
""".stripIndent()
  }

  private static String windowsConfigUpdateCommand(Path installDir, String newMainJar, String newVersion) {
    String escapedInstallDir = installDir.toString().replace('\\', '\\\\')
    String versionCommand = newVersion.isEmpty()
        ? ''
        : "  (Get-Content -LiteralPath \$cfg.FullName) -replace '^java-options=-Djpackage.app-version=.*', 'java-options=-Djpackage.app-version=${newVersion}' | Set-Content -LiteralPath \$cfg.FullName\n"
    """\
powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-ChildItem -LiteralPath '${escapedInstallDir}' -Filter '*.cfg' | ForEach-Object { \$cfg = \$_; (Get-Content -LiteralPath \$cfg.FullName) -replace '^app.classpath=\\\$APPDIR[/\\\\]app-.*\\.jar', 'app.classpath=\\\$APPDIR/${newMainJar}' | Set-Content -LiteralPath \$cfg.FullName; ${versionCommand.trim()} }"
""".stripIndent()
  }

  private static void launchUpdaterAndExit(Path updaterScript) {
    String osName = System.getProperty('os.name', '').toLowerCase(Locale.ROOT)
    ProcessBuilder processBuilder
    if (osName.contains('win')) {
      processBuilder = new ProcessBuilder('cmd', '/c', 'start', '/min', '', updaterScript.toString())
    } else {
      processBuilder = new ProcessBuilder('bash', updaterScript.toString())
    }
    processBuilder.directory(updaterScript.parent.toFile())
    processBuilder.inheritIO()
    processBuilder.start()
    System.exit(0)
  }

  static boolean isNewer(String remote, String current) {
    if (remote == null || current == null || current == 'dev') {
      return false
    }
    int[] remoteComponents = parseVersion(remote)
    int[] currentComponents = parseVersion(current)
    for (int index = 0; index < Math.max(remoteComponents.length, currentComponents.length); index++) {
      int remoteValue = index < remoteComponents.length ? remoteComponents[index] : 0
      int currentValue = index < currentComponents.length ? currentComponents[index] : 0
      if (remoteValue > currentValue) {
        return true
      }
      if (remoteValue < currentValue) {
        return false
      }
    }
    false
  }

  static int[] parseVersion(String version) {
    version.split('\\.').collect { String part ->
      try {
        Integer.parseInt(part)
      } catch (NumberFormatException ignored) {
        0
      }
    } as int[]
  }

  private static void deleteDirectoryContents(Path directory) {
    if (!Files.isDirectory(directory)) {
      return
    }
    Files.list(directory).withCloseable { stream ->
      stream.each { Path path ->
        if (Files.isDirectory(path)) {
          deleteDirectoryContents(path)
        }
        Files.deleteIfExists(path)
      }
    }
  }

  private static void notifyApplyPhase(Closure<Void> callback, ApplyPhase phase) {
    if (callback != null) {
      callback.call(phase)
    }
  }

  enum ApplyPhase {
    EXTRACTING,
    STAGING,
    LAUNCHING
  }

  private static final class UpdaterScriptContext {

    final Path stagingDir
    final Path extractedDir
    final Path installDir
    final Path updaterLog
    final Path backupDir
    final String launcherCommand
    final String newMainJar
    final String newVersion

    private UpdaterScriptContext(
        Path stagingDir,
        Path extractedDir,
        Path installDir,
        Path updaterLog,
        Path backupDir,
        String launcherCommand,
        String newMainJar,
        String newVersion) {
      this.stagingDir = stagingDir
      this.extractedDir = extractedDir
      this.installDir = installDir
      this.updaterLog = updaterLog
      this.backupDir = backupDir
      this.launcherCommand = launcherCommand
      this.newMainJar = newMainJar
      this.newVersion = newVersion
    }
  }

  static final class UpdateInfo {

    String currentVersion
    String availableVersion
    String downloadUrl
    String checksumUrl
    String releaseNotes
    String releasePageUrl
    boolean updateAvailable
  }
}
