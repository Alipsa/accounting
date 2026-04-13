package se.alipsa.accounting.domain.report

import groovy.transform.Canonical

/**
 * One income-statement account row grouped by section.
 */
@Canonical
final class IncomeStatementRow {

  String section
  String accountNumber
  String accountName
  BigDecimal amount
}
