package se.alipsa.accounting.domain.report

import groovy.transform.Canonical
import groovy.transform.CompileStatic

import java.time.LocalDate

/**
 * One row in the voucher list report.
 */
@Canonical
@CompileStatic
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
