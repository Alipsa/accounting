package se.alipsa.accounting.support

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull

import com.sun.jna.platform.win32.Win32Exception
import com.sun.jna.platform.win32.WinError
import com.sun.jna.platform.win32.WinReg
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Files
import java.nio.file.Path

class GitBashLocatorTest {

  @TempDir
  Path tempDir

  @Test
  void returnsNullOnNonWindows() {
    GitBashLocator locator = new TestGitBashLocator(false, [:])
    assertNull(locator.findBash())
  }

  @Test
  void findsGitBashExeFromRegistryInstallPath() {
    Path gitRoot = tempDir.resolve('Git')
    Path gitBash = gitRoot.resolve('git-bash.exe')
    Files.createDirectories(gitBash.parent)
    Files.createFile(gitBash)
    GitBashLocator locator = new TestGitBashLocator(true,
        [(new Tuple2(WinReg.HKEY_CURRENT_USER, 0)): gitRoot.toString()])

    assertEquals(gitBash.toAbsolutePath().normalize(), locator.findBash())
  }

  @Test
  void fallsBackToBinBashExeWhenGitBashExeIsMissing() {
    Path gitRoot = tempDir.resolve('Git')
    Path bash = gitRoot.resolve('bin/bash.exe')
    Files.createDirectories(bash.parent)
    Files.createFile(bash)
    GitBashLocator locator = new TestGitBashLocator(true,
        [(new Tuple2(WinReg.HKEY_CURRENT_USER, 0)): gitRoot.toString()])

    assertEquals(bash.toAbsolutePath().normalize(), locator.findBash())
  }

  @Test
  void fallsBackToUsrBinBashExeWhenBinBashIsMissing() {
    Path gitRoot = tempDir.resolve('Git')
    Path bash = gitRoot.resolve('usr/bin/bash.exe')
    Files.createDirectories(bash.parent)
    Files.createFile(bash)
    GitBashLocator locator = new TestGitBashLocator(true,
        [(new Tuple2(WinReg.HKEY_CURRENT_USER, 0)): gitRoot.toString()])

    assertEquals(bash.toAbsolutePath().normalize(), locator.findBash())
  }

  @Test
  void returnsNullWhenRegistryInstallPathContainsNoBash() {
    Path gitRoot = tempDir.resolve('Git')
    Files.createDirectories(gitRoot)
    GitBashLocator locator = new TestGitBashLocator(true,
        [(new Tuple2(WinReg.HKEY_CURRENT_USER, 0)): gitRoot.toString()])

    assertNull(locator.findBash())
  }

  private static final class TestGitBashLocator extends GitBashLocator {
    private final boolean windows
    private final Map<Tuple2<WinReg.HKEY, Integer>, String> registryValues

    TestGitBashLocator(boolean windows, Map<Tuple2<WinReg.HKEY, Integer>, String> registryValues) {
      this.windows = windows
      this.registryValues = registryValues
    }

    @Override
    boolean isWindows() { windows }

    @Override
    String readInstallPath(WinReg.HKEY root, int view) {
      String value = registryValues.get(new Tuple2(root, view))
      if (value == null) {
        throw new Win32Exception(WinError.ERROR_FILE_NOT_FOUND)
      }
      value
    }
  }
}
