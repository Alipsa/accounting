package se.alipsa.accounting.ui

import se.alipsa.accounting.domain.Voucher
import se.alipsa.accounting.domain.VoucherLine

import java.time.LocalDate

/** Operations that persist a voucher or create a correction. */
interface VoucherOperations {

  Voucher createVoucher(
      long fiscalYearId,
      String seriesCode,
      LocalDate accountingDate,
      String description,
      List<VoucherLine> lines
  )

  Voucher createCorrectionVoucher(long originalVoucherId, String description)
}
