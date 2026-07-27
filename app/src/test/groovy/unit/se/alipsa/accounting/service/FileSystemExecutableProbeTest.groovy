package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Files
import java.nio.file.Path

class FileSystemExecutableProbeTest {

  @TempDir
  Path tempDir

  @Test
  void acceptsRegularExecutableFile() {
    Path file = tempDir.resolve('script.sh')
    Files.createFile(file)
    file.toFile().setExecutable(true)

    assertTrue(new FileSystemExecutableProbe().isExecutableFile(file))
  }

  @Test
  void rejectsRegularNonExecutableFile() {
    Path file = tempDir.resolve('script.sh')
    Files.createFile(file)
    file.toFile().setExecutable(false)

    assertFalse(new FileSystemExecutableProbe().isExecutableFile(file))
  }

  @Test
  void rejectsDirectory() {
    Path dir = tempDir.resolve('bin')
    Files.createDirectory(dir)

    assertFalse(new FileSystemExecutableProbe().isExecutableFile(dir))
  }

  @Test
  void rejectsMissingPath() {
    assertFalse(new FileSystemExecutableProbe().isExecutableFile(tempDir.resolve('missing')))
  }

  @Test
  void acceptsWindowsAppExecutionAliasForExe() {
    FileSystemExecutableProbe.AppExecutionAliasDetector alias = { Path candidate ->
      candidate.toString().endsWith('wt.exe')
    } as FileSystemExecutableProbe.AppExecutionAliasDetector
    FileSystemExecutableProbe probe = new FileSystemExecutableProbe(true, alias)

    assertTrue(probe.isExecutableFile(Path.of('C:\\Users\\x\\AppData\\Local\\Microsoft\\WindowsApps\\wt.exe')))
  }

  @Test
  void rejectsNonExeReparsePointEvenUnderWindowsApps() {
    FileSystemExecutableProbe.AppExecutionAliasDetector alias = { Path candidate -> true } as FileSystemExecutableProbe.AppExecutionAliasDetector
    FileSystemExecutableProbe probe = new FileSystemExecutableProbe(true, alias)

    assertFalse(probe.isExecutableFile(Path.of('C:\\Users\\x\\AppData\\Local\\Microsoft\\WindowsApps\\wt')))
    assertFalse(probe.isExecutableFile(Path.of('C:\\Users\\x\\AppData\\Local\\Microsoft\\WindowsApps\\wt.bat')))
  }

  @Test
  void ignoresAliasDetectorOnNonWindows() {
    FileSystemExecutableProbe.AppExecutionAliasDetector alias = { Path candidate -> true } as FileSystemExecutableProbe.AppExecutionAliasDetector
    FileSystemExecutableProbe probe = new FileSystemExecutableProbe(false, alias)

    assertFalse(probe.isExecutableFile(Path.of('C:\\Users\\x\\AppData\\Local\\Microsoft\\WindowsApps\\wt.exe')))
  }

  @Test
  void rejectsAliasWhenDetectorReturnsFalse() {
    FileSystemExecutableProbe.AppExecutionAliasDetector alias = { Path candidate -> false } as FileSystemExecutableProbe.AppExecutionAliasDetector
    FileSystemExecutableProbe probe = new FileSystemExecutableProbe(true, alias)

    assertFalse(probe.isExecutableFile(Path.of('C:\\Users\\x\\AppData\\Local\\Microsoft\\WindowsApps\\wt.exe')))
  }
}
