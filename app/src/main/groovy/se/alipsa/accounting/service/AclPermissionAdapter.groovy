package se.alipsa.accounting.service

import java.nio.file.Path

/** Applies and verifies owner-only file-system ACLs. */
interface AclPermissionAdapter {
  void applyOwnerOnly(Path path)
  void verifyOwnerOnly(Path path)
}
