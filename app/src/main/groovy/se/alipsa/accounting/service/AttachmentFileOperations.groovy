package se.alipsa.accounting.service

import groovy.transform.PackageScope

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Filesystem operations used by AttachmentService, injectable for crash-recovery tests.
 */
@PackageScope
interface AttachmentFileOperations {

  void copy(Path source, Path target) throws IOException

  boolean deleteIfExists(Path path) throws IOException
}

/**
 * Production implementation backed by java.nio.file.Files.
 */
@PackageScope
final class DefaultAttachmentFileOperations implements AttachmentFileOperations {

  @Override
  void copy(Path source, Path target) throws IOException {
    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
  }

  @Override
  boolean deleteIfExists(Path path) throws IOException {
    Files.deleteIfExists(path)
  }
}
