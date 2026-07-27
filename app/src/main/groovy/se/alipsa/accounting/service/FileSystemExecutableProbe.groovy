package se.alipsa.accounting.service

import groovy.transform.PackageScope

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Filesystem-backed executable probe. */
final class FileSystemExecutableProbe implements ExecutableProbe {

  private final boolean windows
  private final AppExecutionAliasDetector aliasDetector

  FileSystemExecutableProbe() {
    this(isWindowsOs(), new NioAppExecutionAliasDetector())
  }

  @PackageScope
  FileSystemExecutableProbe(boolean windows, AppExecutionAliasDetector aliasDetector) {
    this.windows = windows
    this.aliasDetector = aliasDetector
  }

  @Override
  boolean isExecutableFile(Path candidate) {
    if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) { return true }
    if (windows && hasExeExtension(candidate) && aliasDetector.isAppExecutionAlias(candidate)) { return true }
    false
  }

  private static boolean hasExeExtension(Path candidate) {
    candidate.toString().toLowerCase(Locale.ROOT).endsWith('.exe')
  }

  private static boolean isWindowsOs() {
    String osName = System.getProperty('os.name') ?: ''
    osName.toLowerCase(Locale.ROOT).contains('win')
  }

  /** Test seam: decides whether a path is a Windows Store App Execution Alias reparse point. */
  @PackageScope
  interface AppExecutionAliasDetector { boolean isAppExecutionAlias(Path candidate) }

  private static final class NioAppExecutionAliasDetector implements AppExecutionAliasDetector {
    @Override
    boolean isAppExecutionAlias(Path candidate) {
      try {
        BasicFileAttributes attributes = Files.readAttributes(candidate, BasicFileAttributes, LinkOption.NOFOLLOW_LINKS)
        return attributes.isOther()
      } catch (Exception ignored) {
        return false
      }
    }
  }
}
