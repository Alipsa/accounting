package se.alipsa.accounting.service

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/** Fail-closed permissions and symlink-chain handling for the AI workspace. */
final class AiWorkspacePermissions {

  static final Set<PosixFilePermission> EXECUTABLE_PERMISSIONS = EnumSet.of(
      PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
  static final Set<PosixFilePermission> DATA_PERMISSIONS = EnumSet.of(
      PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

  private final AclPermissionAdapter aclAdapter

  AiWorkspacePermissions() { this(new RealAclPermissionAdapter()) }

  AiWorkspacePermissions(AclPermissionAdapter aclAdapter) { this.aclAdapter = aclAdapter }

  void ensureDirectory(Path root, Path dir) {
    Path normalizedRoot = root.toAbsolutePath().normalize()
    Path normalizedDir = dir.toAbsolutePath().normalize()
    if (normalizedDir != normalizedRoot && !normalizedDir.startsWith(normalizedRoot)) {
      throw new IllegalArgumentException("${dir} is not inside workspace ${root}.")
    }
    ensureWithinRoot(normalizedRoot, normalizedDir)
  }

  private void ensureWithinRoot(Path root, Path dir) {
    if (Files.isSymbolicLink(dir)) {
      throw new IllegalStateException("Refusing to operate through a symlink at ${dir}.")
    }
    if (Files.isDirectory(dir)) {
      applyAndVerify(dir, SecretFileKind.EXECUTABLE)
      return
    }
    if (Files.exists(dir)) {
      throw new IllegalStateException("${dir} exists but is not a directory.")
    }
    if (dir == root) {
      Path parent = dir.parent
      if (parent == null || !Files.isDirectory(parent) || Files.isSymbolicLink(parent)) {
        throw new IllegalStateException("Workspace parent ${parent} must be a real existing directory.")
      }
    } else {
      ensureWithinRoot(root, dir.parent)
    }
    createDirectory(dir)
    applyAndVerify(dir, SecretFileKind.EXECUTABLE)
  }

  void createFileWithPermissions(Path path, SecretFileKind kind) {
    createFileWithPermissions(path, kind, path.fileSystem.supportedFileAttributeViews())
  }

  void createFileWithPermissions(Path path, SecretFileKind kind, Set<String> supportedViews) {
    if (supportedViews.contains('posix')) {
      Files.createFile(path, PosixFilePermissions.asFileAttribute(permissionsFor(kind)))
      verifyPosix(path, kind)
      return
    }
    if (supportedViews.contains('acl')) {
      Files.createFile(path)
      aclAdapter.applyOwnerOnly(path)
      aclAdapter.verifyOwnerOnly(path)
      return
    }
    throw new IllegalStateException("Neither POSIX permissions nor ACLs are supported for ${path}.")
  }

  void applyAndVerify(Path path, SecretFileKind kind) {
    applyAndVerify(path, kind, path.fileSystem.supportedFileAttributeViews())
  }

  void applyAndVerify(Path path, SecretFileKind kind, Set<String> supportedViews) {
    if (supportedViews.contains('posix')) {
      Files.setPosixFilePermissions(path, permissionsFor(kind))
      verifyPosix(path, kind)
      return
    }
    if (supportedViews.contains('acl')) {
      aclAdapter.applyOwnerOnly(path)
      aclAdapter.verifyOwnerOnly(path)
      return
    }
    throw new IllegalStateException("Neither POSIX permissions nor ACLs are supported for ${path}.")
  }

  void verifyNoSymlinksInPath(Path root, Path candidate) {
    Path normalizedRoot = root.toAbsolutePath().normalize()
    Path normalizedCandidate = candidate.toAbsolutePath().normalize()
    if (!normalizedCandidate.startsWith(normalizedRoot)) {
      throw new IllegalArgumentException("${candidate} is not inside workspace ${root}.")
    }
    Path current = normalizedRoot
    if (Files.isSymbolicLink(current)) {
      throw new IllegalStateException("Refusing to operate through a symlink at ${current}.")
    }
    for (Path segment : normalizedRoot.relativize(normalizedCandidate)) {
      current = current.resolve(segment)
      if (Files.isSymbolicLink(current)) {
        throw new IllegalStateException("Refusing to operate through a symlink at ${current}.")
      }
    }
  }

  private static void createDirectory(Path dir) {
    Set<String> views = dir.fileSystem.supportedFileAttributeViews()
    if (views.contains('posix')) {
      Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(EXECUTABLE_PERMISSIONS))
      return
    }
    if (views.contains('acl')) {
      Files.createDirectory(dir)
      return
    }
    throw new IllegalStateException("Neither POSIX permissions nor ACLs are supported for ${dir}.")
  }

  private static Set<PosixFilePermission> permissionsFor(SecretFileKind kind) {
    kind == SecretFileKind.EXECUTABLE ? EXECUTABLE_PERMISSIONS : DATA_PERMISSIONS
  }

  private static void verifyPosix(Path path, SecretFileKind kind) {
    if (Files.getPosixFilePermissions(path) != permissionsFor(kind)) {
      throw new IllegalStateException("Unexpected permissions on ${path}.")
    }
  }
}
