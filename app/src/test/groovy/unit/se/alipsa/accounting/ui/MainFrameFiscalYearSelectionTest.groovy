package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.assertSame

import org.junit.jupiter.api.Test

import se.alipsa.accounting.domain.FiscalYear

import java.time.LocalDate

final class MainFrameFiscalYearSelectionTest {

  @Test
  void prefersTheActiveCompanyManagersFiscalYearOverTheComboBoxsStaleSelection() {
    FiscalYear staleComboSelection = new FiscalYear(
        1L, '2029', LocalDate.of(2029, 1, 1), LocalDate.of(2029, 12, 31), false, null)
    FiscalYear newlyCreatedYear = new FiscalYear(
        2L, '2030', LocalDate.of(2030, 1, 1), LocalDate.of(2030, 12, 31), false, null)

    FiscalYear result = MainFrame.resolveFiscalYearToSelect(staleComboSelection, newlyCreatedYear)

    assertSame(newlyCreatedYear, result)
  }

  @Test
  void fallsBackToTheComboBoxsSelectionWhenThereIsNoActiveFiscalYearYet() {
    FiscalYear staleComboSelection = new FiscalYear(
        1L, '2029', LocalDate.of(2029, 1, 1), LocalDate.of(2029, 12, 31), false, null)

    FiscalYear result = MainFrame.resolveFiscalYearToSelect(staleComboSelection, null)

    assertSame(staleComboSelection, result)
  }
}
