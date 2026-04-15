package se.alipsa.accounting.domain

import groovy.transform.Canonical

import java.time.LocalDate

/**
 * Accounting voucher with editable lines until its period is locked.
 */
@Canonical
final class Voucher {

  Long id
  long fiscalYearId
  long voucherSeriesId
  String seriesCode
  String seriesName
  Integer runningNumber
  String voucherNumber
  LocalDate accountingDate
  String description
  VoucherStatus status
  Long originalVoucherId
  List<VoucherLine> lines = []

  BigDecimal debitTotal() {
    lines.sum(BigDecimal.ZERO) { VoucherLine line -> line.debitAmount ?: BigDecimal.ZERO } as BigDecimal
  }

  BigDecimal creditTotal() {
    lines.sum(BigDecimal.ZERO) { VoucherLine line -> line.creditAmount ?: BigDecimal.ZERO } as BigDecimal
  }
}
