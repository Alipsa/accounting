package se.alipsa.accounting.domain.report

import groovy.transform.Canonical

/**
 * One balance-sheet account row grouped by section.
 */
@Canonical
final class BalanceSheetRow {

  String section
  String accountNumber
  String accountName
  BigDecimal amount
}
