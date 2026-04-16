package se.alipsa.accounting.domain.report

import groovy.transform.Canonical

/**
 * One balance-sheet row — an account detail line, a subgroup subtotal, or a computed total.
 */
@Canonical
final class BalanceSheetRow {

  String section
  String accountNumber
  String accountName
  BigDecimal amount
  String subgroupDisplayName
  boolean summaryRow
}
