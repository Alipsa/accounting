package se.alipsa.accounting.service

import java.nio.file.Path
import java.nio.file.Paths

/** Resolves an executable by scanning PATH and, when provided, PATHEXT. */
final class PathBinaryResolver {

  private final EnvironmentLookup environmentLookup
  private final ExecutableProbe executableProbe

  PathBinaryResolver(EnvironmentLookup environmentLookup, ExecutableProbe executableProbe) {
    this.environmentLookup = environmentLookup
    this.executableProbe = executableProbe
  }

  Path resolve(String binaryName) {
    String path = environmentLookup.getenv('PATH')
    if (!path) { return null }
    List<String> suffixes = ['']
    String pathext = environmentLookup.getenv('PATHEXT')
    if (pathext) {
      pathext.split(';').each { String extension -> if (extension?.trim()) { suffixes << extension.trim() } }
    }
    for (String directory : path.split(java.io.File.pathSeparator)) {
      if (!directory) { continue }
      for (String suffix : suffixes) {
        Path candidate = Paths.get(directory, binaryName + suffix)
        if (executableProbe.isExecutableFile(candidate)) { return candidate.toAbsolutePath().normalize() }
      }
    }
    null
  }
}
