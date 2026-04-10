package se.alipsa.accounting.domain

import groovy.transform.Canonical
import groovy.transform.CompileStatic

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Accounting voucher with immutable booked lines and hash-chain metadata.
 */
@Canonical
@CompileStatic
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
  String previousHash
  String contentHash
  LocalDateTime bookedAt
  List<VoucherLine> lines = []

  BigDecimal debitTotal() {
    lines.sum(BigDecimal.ZERO) { VoucherLine line -> line.debitAmount ?: BigDecimal.ZERO } as BigDecimal
  }

  BigDecimal creditTotal() {
    lines.sum(BigDecimal.ZERO) { VoucherLine line -> line.creditAmount ?: BigDecimal.ZERO } as BigDecimal
  }
}
