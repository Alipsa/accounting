package se.alipsa.accounting.support

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Win32Exception
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinReg

import java.nio.file.Files
import java.nio.file.Path

/** Locates a Git Bash installation by reading the Git for Windows registry key. */
class GitBashLocator {

  private static final String REGISTRY_KEY = 'Software\\GitForWindows'

  Path findBash() {
    if (!isWindows()) {
      return null
    }
    WinReg.HKEY[] roots = [WinReg.HKEY_CURRENT_USER, WinReg.HKEY_LOCAL_MACHINE]
    int[] views = [WinNT.KEY_WOW64_64KEY, WinNT.KEY_WOW64_32KEY, 0]
    for (WinReg.HKEY root : roots) {
      for (int view : views) {
        try {
          String installPath = readInstallPath(root, view)
          Path bash = findBashWithin(Path.of(installPath))
          if (bash != null) {
            return bash
          }
        } catch (Win32Exception ignored) {
          // Key or registry view does not exist.
        }
      }
    }
    null
  }

  protected String readInstallPath(WinReg.HKEY root, int view) {
    Advapi32Util.registryGetStringValue(root, REGISTRY_KEY, 'InstallPath', view)
  }

  protected boolean isWindows() {
    System.getProperty('os.name', '').toLowerCase(Locale.ROOT).contains('win')
  }

  private static Path findBashWithin(Path gitRoot) {
    Path[] candidates = [gitRoot.resolve('git-bash.exe'), gitRoot.resolve('usr/bin/bash.exe'),
        gitRoot.resolve('bin/bash.exe')]
    for (Path candidate : candidates) {
      if (Files.isRegularFile(candidate)) {
        return candidate.toAbsolutePath().normalize()
      }
    }
    null
  }
}
