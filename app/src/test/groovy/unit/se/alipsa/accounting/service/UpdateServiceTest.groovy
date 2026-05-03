package unit.se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.*

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

import se.alipsa.accounting.service.UpdateService

import java.nio.file.Path

final class UpdateServiceTest {

  @ParameterizedTest
  @CsvSource([
      '2.0.0, 1.0.0, true',
      '1.1.0, 1.0.0, true',
      '1.0.1, 1.0.0, true',
      '1.0.0, 1.0.0, false',
      '1.0.0, 2.0.0, false',
      '1.0.0, 1.1.0, false',
      '1.0.0, 1.0.1, false',
      '1.0,   1.0.0, false',
      '1.0.0, 1.0,   false',
      '1.1,   1.0.0, true',
      '1.0.0, 1.1,   false',
  ])
  void isNewerComparesVersionsCorrectly(String remote, String current, boolean expected) {
    assertEquals(expected, UpdateService.isNewer(remote, current))
  }

  @Test
  void isNewerReturnsFalseForNullRemote() {
    assertFalse(UpdateService.isNewer(null, '1.0.0'))
  }

  @Test
  void isNewerReturnsFalseForNullCurrent() {
    assertFalse(UpdateService.isNewer('1.0.0', null))
  }

  @Test
  void isNewerReturnsFalseForDevCurrent() {
    assertFalse(UpdateService.isNewer('1.0.0', 'dev'))
  }

  @Test
  void parseVersionHandlesStandardVersion() {
    assertArrayEquals([1, 2, 3] as int[], UpdateService.parseVersion('1.2.3'))
  }

  @Test
  void parseVersionHandlesTwoPartVersion() {
    assertArrayEquals([1, 0] as int[], UpdateService.parseVersion('1.0'))
  }

  @Test
  void parseVersionHandlesSingleNumber() {
    assertArrayEquals([5] as int[], UpdateService.parseVersion('5'))
  }

  @Test
  void parseVersionTreatsNonNumericAsZero() {
    assertArrayEquals([1, 0, 0] as int[], UpdateService.parseVersion('1.beta.0'))
  }

  @Test
  void launcherPathUsesJpackageAppImageRootOnLinux() {
    assertEquals(
        Path.of('/opt/AlipsaAccounting/bin/AlipsaAccounting'),
        UpdateService.launcherPath(Path.of('/opt/AlipsaAccounting/lib/app'), 'linux')
    )
  }

  @Test
  void launcherPathUsesJpackageAppImageRootOnWindows() {
    assertEquals(
        Path.of('C:/Programs/AlipsaAccounting/AlipsaAccounting.exe'),
        UpdateService.launcherPath(Path.of('C:/Programs/AlipsaAccounting/lib/app'), 'windows')
    )
  }

  @Test
  void launcherPathUsesJpackageAppImageRootOnMacos() {
    assertEquals(
        Path.of('/Applications/AlipsaAccounting.app/Contents/MacOS/AlipsaAccounting'),
        UpdateService.launcherPath(Path.of('/Applications/AlipsaAccounting.app/Contents/app'), 'mac os x')
    )
  }
}
