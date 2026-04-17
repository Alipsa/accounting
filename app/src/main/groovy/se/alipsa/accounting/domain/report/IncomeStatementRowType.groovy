package se.alipsa.accounting.domain.report

/**
 * Visual row categories used to render the income statement with clearer hierarchy.
 */
enum IncomeStatementRowType {

  SECTION_HEADER,
  GROUP_HEADER,
  DETAIL,
  SUBTOTAL,
  SECTION_TOTAL,
  RESULT_LINE,
  GRAND_TOTAL
}
