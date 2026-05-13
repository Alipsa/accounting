package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Files
import java.nio.file.Path

import javax.swing.filechooser.FileNameExtensionFilter

final class SieExchangeDialogTest {

  @TempDir
  Path tempDir

  @Test
  void importFilterAcceptsUpperAndLowerCaseSieExtensions() {
    FileNameExtensionFilter filter = SieExchangeDialog.sieImportFileFilter()

    assertTrue(filter.accept(Files.createFile(tempDir.resolve('lower.sie')).toFile()))
    assertTrue(filter.accept(Files.createFile(tempDir.resolve('upper.SE')).toFile()))
    assertTrue(filter.accept(Files.createFile(tempDir.resolve('upper.SIE')).toFile()))
  }
}
