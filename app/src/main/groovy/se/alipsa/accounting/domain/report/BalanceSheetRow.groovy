package se.alipsa.accounting.domain.report

import groovy.transform.Canonical
import groovy.transform.CompileStatic

/**
 * One balance-sheet account row grouped by section.
 */
@Canonical
@CompileStatic
final class BalanceSheetRow {

  String section
  String accountNumber
  String accountName
  BigDecimal amount
}
