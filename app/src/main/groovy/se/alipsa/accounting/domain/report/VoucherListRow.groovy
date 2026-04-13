package se.alipsa.accounting.domain.report

import groovy.transform.Canonical

import java.time.LocalDate

/**
 * One row in the voucher list report.
 */
@Canonical
final class VoucherListRow {

  Long voucherId
  LocalDate accountingDate
  String voucherNumber
  String seriesCode
  String description
  String status
  BigDecimal debitAmount
  BigDecimal creditAmount
}
