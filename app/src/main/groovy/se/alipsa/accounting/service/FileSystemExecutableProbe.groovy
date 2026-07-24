package se.alipsa.accounting.service

import java.nio.file.Files
import java.nio.file.Path

/** Filesystem-backed executable probe. */
final class FileSystemExecutableProbe implements ExecutableProbe {
  @Override
  boolean isExecutableFile(Path candidate) { Files.isRegularFile(candidate) && Files.isExecutable(candidate) }
}
