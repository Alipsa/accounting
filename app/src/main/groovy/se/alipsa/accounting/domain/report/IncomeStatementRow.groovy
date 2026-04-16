package se.alipsa.accounting.domain.report

import groovy.transform.Canonical

/**
 * One income-statement row — either an account-subgroup line or a computed summary/result row.
 */
@Canonical
final class IncomeStatementRow {

  String section
  BigDecimal amount
  String subgroupDisplayName
  boolean summaryRow
}
