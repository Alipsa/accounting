package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assumptions.assumeTrue

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Files
import java.nio.file.Path

class AiWorkspacePermissionsTest {

  @TempDir
  Path tempDir

  @Test
  void rejectsASymlinkedWorkspacePathComponent() {
    assumeTrue(!System.getProperty('os.name', '').toLowerCase(Locale.ROOT).contains('win'))
    Path root = tempDir.resolve('workspace')
    Path outside = tempDir.resolve('outside')
    Files.createDirectories(root)
    Files.createDirectories(outside)
    Path linkedConfigDirectory = root.resolve('.codex')
    Files.createSymbolicLink(linkedConfigDirectory, outside)

    assertThrows(IllegalStateException) {
      new AiWorkspacePermissions().verifyNoSymlinksInPath(root, linkedConfigDirectory.resolve('config.toml'))
    }
  }
}
