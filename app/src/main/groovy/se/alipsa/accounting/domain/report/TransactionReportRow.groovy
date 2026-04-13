package se.alipsa.accounting.domain.report

import groovy.transform.Canonical

import java.time.LocalDate

/**
 * One voucher-line row in the transaction report.
 */
@Canonical
final class TransactionReportRow {

  Long voucherId
  LocalDate accountingDate
  String voucherNumber
  String accountNumber
  String accountName
  String voucherDescription
  String lineDescription
  BigDecimal debitAmount
  BigDecimal creditAmount
  String status
}
