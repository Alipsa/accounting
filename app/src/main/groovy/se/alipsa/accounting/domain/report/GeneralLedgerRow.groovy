package se.alipsa.accounting.domain.report

import groovy.transform.Canonical
import groovy.transform.CompileStatic

import java.time.LocalDate

/**
 * One general-ledger posting row, optionally including an opening balance line.
 */
@Canonical
@CompileStatic
final class GeneralLedgerRow {

  String accountNumber
  String accountName
  LocalDate accountingDate
  String voucherNumber
  String description
  BigDecimal debitAmount
  BigDecimal creditAmount
  BigDecimal balance
  Long voucherId
}
