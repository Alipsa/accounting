package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

import com.formdev.flatlaf.util.SystemFileChooser.FileNameExtensionFilter
import org.junit.jupiter.api.Test

import se.alipsa.accounting.domain.FiscalYear

import java.time.LocalDate

final class SieExchangeDialogTest {

  @Test
  void importFilterIncludesUpperAndLowerCaseSieExtensions() {
    FileNameExtensionFilter filter = SieExchangeDialog.sieImportFileFilter()

    List<String> extensions = filter.extensions.toList()
    assertTrue(extensions.containsAll(['sie', 'SIE', 'si', 'SI', 'se', 'SE']))
  }

  @Test
  void initialFiscalYearSelectionUsesTheActiveFiscalYear() {
    FiscalYear activeYear = new FiscalYear(1L, '2025', LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), false, null)
    FiscalYear latestYear = new FiscalYear(2L, '2026', LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), false, null)

    assertEquals(activeYear.id, SieExchangeDialog.initialFiscalYearId([latestYear, activeYear], activeYear.id))
  }
}
