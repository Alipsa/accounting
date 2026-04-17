package se.alipsa.accounting.domain.report

import groovy.transform.Canonical

/**
 * One income-statement row with enough metadata to render hierarchical layouts in preview/export.
 */
@Canonical
final class IncomeStatementRow {

  String section
  String displayLabel
  BigDecimal amount
  IncomeStatementRowType rowType
}
