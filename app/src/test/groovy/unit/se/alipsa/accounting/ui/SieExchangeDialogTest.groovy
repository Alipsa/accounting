package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.assertTrue

import com.formdev.flatlaf.util.SystemFileChooser.FileNameExtensionFilter
import org.junit.jupiter.api.Test

final class SieExchangeDialogTest {

  @Test
  void importFilterIncludesUpperAndLowerCaseSieExtensions() {
    FileNameExtensionFilter filter = SieExchangeDialog.sieImportFileFilter()

    List<String> extensions = filter.extensions.toList()
    assertTrue(extensions.containsAll(['sie', 'SIE', 'si', 'SI', 'se', 'SE']))
  }
}
