package se.alipsa.accounting.domain.report

import groovy.transform.Canonical

/**
 * One account summary row in the trial balance report.
 */
@Canonical
final class TrialBalanceRow {

  String accountNumber
  String accountName
  BigDecimal openingBalance
  BigDecimal debitAmount
  BigDecimal creditAmount
  BigDecimal closingBalance
}
