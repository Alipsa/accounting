package se.alipsa.accounting.service

import groovy.json.JsonSlurper
import groovy.transform.CompileDynamic

import se.alipsa.accounting.support.AppPaths

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Checks for application updates via the GitHub releases API,
 * downloads the platform-independent distribution, and stages
 * a restart with the updated JARs.
 */
final class UpdateService {

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
    List<Map<String, Object>> assets = release.get('assets') as List<Map<String, Object>>
    if (assets != null) {
      for (Map<String, Object> asset : assets) {
        String name = asset.get('name') as String
        if (name != null && name.startsWith(DIST_ASSET_PREFIX) && name.endsWith('.zip')) {
          downloadUrl = asset.get('browser_download_url') as String
          break
        }
      }
    }

    new UpdateInfo(
        currentVersion: currentVersion(),
        availableVersion: remoteVersion,
        downloadUrl: downloadUrl,
        releaseNotes: releaseNotes,
        releasePageUrl: htmlUrl,
        updateAvailable: isNewer(remoteVersion, currentVersion())
    )
  }

  Path downloadUpdate(UpdateInfo info, Closure<Void> progressCallback) {
    if (info.downloadUrl == null) {
      throw new IllegalStateException('No download URL available for the update.')
    }

    Path stagingDir = stagingDirectory()
    Files.createDirectories(stagingDir)
    Path targetFile = stagingDir.resolve("app-${info.availableVersion}.zip")

    HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(info.downloadUrl))
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
    targetFile
  }

  void applyUpdateAndRestart(Path downloadedZip) {
    Path stagingDir = stagingDirectory()
    Path extractedDir = stagingDir.resolve('extracted')
    deleteDirectoryContents(extractedDir)
    Files.createDirectories(extractedDir)

    extractJars(downloadedZip, extractedDir)

    Path installDir = installationJarDirectory()
    if (installDir == null) {
      throw new IllegalStateException('Cannot determine installation JAR directory.')
    }

    Path updaterScript = writeUpdaterScript(stagingDir, extractedDir, installDir)
    launchUpdaterAndExit(updaterScript)
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
    String osName = System.getProperty('os.name', '').toLowerCase(Locale.ROOT)
    if (osName.contains('win')) {
      return jarDir.parent.resolve('AlipsaAccounting.exe')
    }
    if (osName.contains('mac')) {
      return jarDir.parent.resolve('MacOS').resolve('AlipsaAccounting')
    }
    jarDir.parent.resolve('bin').resolve('AlipsaAccounting')
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

  private Path writeUpdaterScript(Path stagingDir, Path extractedDir, Path installDir) {
    String osName = System.getProperty('os.name', '').toLowerCase(Locale.ROOT)
    boolean isWindows = osName.contains('win')
    Path launcher = launcherPath()
    String launcherCommand = launcher != null ? "\"${launcher}\"" : ''

    Path script
    if (isWindows) {
      script = stagingDir.resolve('updater.bat')
      script.toFile().text = """\
@echo off
timeout /t 3 /nobreak >nul
del /q "${installDir}\\*.jar"
copy /y "${extractedDir}\\*.jar" "${installDir}\\"
rd /s /q "${extractedDir}"
del "${stagingDir}\\*.zip"
${launcherCommand.isEmpty() ? 'echo Update complete.' : "start \"\" ${launcherCommand}"}
del "%~f0"
""".stripIndent()
    } else {
      script = stagingDir.resolve('updater.sh')
      script.toFile().text = """\
#!/usr/bin/env bash
sleep 3
rm -f "${installDir}/"*.jar
cp "${extractedDir}/"*.jar "${installDir}/"
rm -rf "${extractedDir}"
rm -f "${stagingDir}/"*.zip
${launcherCommand.isEmpty() ? 'echo "Update complete."' : "exec ${launcherCommand} &"}
rm -f "\$0"
""".stripIndent()
      script.toFile().setExecutable(true)
    }
    script
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

  private static boolean isNewer(String remote, String current) {
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

  private static int[] parseVersion(String version) {
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

  static final class UpdateInfo {

    String currentVersion
    String availableVersion
    String downloadUrl
    String releaseNotes
    String releasePageUrl
    boolean updateAvailable
  }
}
