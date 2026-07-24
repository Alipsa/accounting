package se.alipsa.accounting.service

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.UserPrincipal

/** Owner-only ACL implementation for ACL-capable file systems. */
final class RealAclPermissionAdapter implements AclPermissionAdapter {

  @Override
  void applyOwnerOnly(Path path) {
    AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView)
    UserPrincipal owner = Files.getOwner(path)
    AclEntry entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
        .setPermissions(AclEntryPermission.values() as Set).build()
    view.setAcl([entry])
  }

  @Override
  void verifyOwnerOnly(Path path) {
    AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView)
    UserPrincipal owner = Files.getOwner(path)
    List<AclEntry> acl = view.getAcl()
    boolean valid = acl.size() == 1 && acl[0].principal() == owner && acl[0].type() == AclEntryType.ALLOW &&
        acl[0].permissions() == (AclEntryPermission.values() as Set)
    if (!valid) {
      throw new IllegalStateException("Expected an owner-only ACL on ${path}, found ${acl}.")
    }
  }
}
