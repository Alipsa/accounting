package se.alipsa.accounting.domain.report

import groovy.transform.Canonical
import groovy.transform.CompileStatic

/**
 * One income-statement account row grouped by section.
 */
@Canonical
@CompileStatic
final class IncomeStatementRow {

  String section
  String accountNumber
  String accountName
  BigDecimal amount
}
