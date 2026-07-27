package se.alipsa.accounting.service

import groovy.transform.PackageScope

import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Stream

/**
 * Filesystem operations used by ReportArchiveService, injectable for crash-recovery tests.
 */
@PackageScope
interface ReportArchiveFileOperations {

  void write(Path target, byte[] content) throws IOException

  boolean deleteIfExists(Path path) throws IOException

  byte[] readAllBytes(Path path) throws IOException

  boolean isRegularFile(Path path)

  Stream<Path> walk(Path root) throws IOException
}

/**
 * Production implementation backed by java.nio.file.Files.
 */
@PackageScope
final class DefaultReportArchiveFileOperations implements ReportArchiveFileOperations {

  @Override
  void write(Path target, byte[] content) throws IOException {
    Files.write(target, content)
  }

  @Override
  boolean deleteIfExists(Path path) throws IOException {
    Files.deleteIfExists(path)
  }

  @Override
  byte[] readAllBytes(Path path) throws IOException {
    Files.readAllBytes(path)
  }

  @Override
  boolean isRegularFile(Path path) {
    Files.isRegularFile(path)
  }

  @Override
  Stream<Path> walk(Path root) throws IOException {
    Files.walk(root)
  }
}
