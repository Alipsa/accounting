package se.alipsa.accounting.domain.report

/**
 * Visual row categories used to render the balance sheet with statement-style hierarchy.
 */
enum BalanceSheetRowType {

  SECTION_HEADER,
  DETAIL,
  SUBGROUP_TOTAL,
  SECTION_TOTAL,
  GRAND_TOTAL
}
