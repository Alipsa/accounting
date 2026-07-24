package se.alipsa.accounting.service

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Atomically writes a secret-bearing file after verifying its symlink chain. */
final class AtomicSecretFileWriter implements SecretFileWriter {

  private final AiWorkspacePermissions permissions
  private final FileMover fileMover

  AtomicSecretFileWriter() {
    this(new AiWorkspacePermissions(), { Path from, Path to ->
      Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } as FileMover)
  }

  AtomicSecretFileWriter(AiWorkspacePermissions permissions, FileMover fileMover) {
    this.permissions = permissions
    this.fileMover = fileMover
  }

  @Override
  void write(Path root, Path target, byte[] content, SecretFileKind kind) {
    permissions.verifyNoSymlinksInPath(root, target)
    Path tempFile = target.parent.resolve("${target.fileName}.tmp-${UUID.randomUUID()}")
    try {
      permissions.createFileWithPermissions(tempFile, kind)
      Files.write(tempFile, content)
      permissions.verifyNoSymlinksInPath(root, target)
      try {
        fileMover.move(tempFile, target)
      } catch (AtomicMoveNotSupportedException exception) {
        throw new IllegalStateException("Atomic write is not supported for ${target}.", exception)
      }
      permissions.verifyNoSymlinksInPath(root, target)
      permissions.applyAndVerify(target, kind)
    } finally {
      Files.deleteIfExists(tempFile)
    }
  }
}
