package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull

import org.junit.jupiter.api.Test

import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.Voucher
import se.alipsa.accounting.domain.VoucherLine

import java.time.LocalDate

final class VoucherEditorActionsTest {

  @Test
  void saveReturnsNullAndReportsTheErrorWhenSavingFails() {
    List<String> errors = []
    List<String> infos = []
    List<Voucher> savedVouchers = []
    VoucherOperations failingSave = new VoucherOperations() {
      @Override
      Voucher createVoucher(long fiscalYearId, String seriesCode, LocalDate accountingDate,
                            String description, List<VoucherLine> lines) {
        throw new IllegalStateException('Could not save voucher')
      }

      @Override
      Voucher createCorrectionVoucher(long originalVoucherId, String description) {
        null
      }
    }
    VoucherEditorActions actions = new VoucherEditorActions(
        failingSave,
        { new FiscalYear(1L, '2030', LocalDate.of(2030, 1, 1), LocalDate.of(2030, 12, 31), false, null) },
        { LocalDate.of(2030, 1, 10) },
        { 'Test voucher' },
        { [] },
        { null },
        { 'A' },
        { String message -> infos << message },
        { String message -> errors << message },
        { Voucher voucher -> savedVouchers << voucher }
    )

    assertNull(actions.save())
    assertEquals(['Could not save voucher'], errors)
    assertEquals([], infos)
    assertEquals([], savedVouchers)
  }
}
