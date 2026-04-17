package se.alipsa.accounting.domain.report

import se.alipsa.accounting.domain.AccountSubgroup
import se.alipsa.accounting.support.I18n

/**
 * Logical sections of the income statement report, each mapping to one or more
 * {@link AccountSubgroup} values. Sections with an empty subgroup list are computed
 * result rows (subtotals).
 *
 * <p><strong>Important:</strong> The constant ordering defines the report layout.
 * Reordering, inserting, or removing constants will change the rendered report.</p>
 */
enum IncomeStatementSection {

  OPERATING_INCOME([
      AccountSubgroup.NET_REVENUE,
      AccountSubgroup.INVOICED_COSTS,
      AccountSubgroup.SECONDARY_INCOME,
      AccountSubgroup.REVENUE_ADJUSTMENTS,
      AccountSubgroup.CAPITALIZED_WORK,
      AccountSubgroup.OTHER_OPERATING_INCOME
  ]),
  OPERATING_EXPENSES([
      AccountSubgroup.RAW_MATERIALS,
      AccountSubgroup.OTHER_EXTERNAL_COSTS,
      AccountSubgroup.PERSONNEL_COSTS,
      AccountSubgroup.DEPRECIATION,
      AccountSubgroup.OTHER_OPERATING_COSTS
  ]),
  OPERATING_RESULT([]),
  FINANCIAL_ITEMS([
      AccountSubgroup.FINANCIAL_INCOME,
      AccountSubgroup.FINANCIAL_COSTS
  ]),
  RESULT_AFTER_FINANCIAL([]),
  RESULT_AFTER_EXTRAORDINARY([]),
  APPROPRIATIONS([
      AccountSubgroup.APPROPRIATIONS
  ]),
  PROFIT_BEFORE_TAX([]),
  TAX([
      AccountSubgroup.TAX_AND_RESULT
  ]),
  NET_RESULT([])

  final List<AccountSubgroup> subgroups

  IncomeStatementSection(List<AccountSubgroup> subgroups) {
    this.subgroups = Collections.unmodifiableList(subgroups)
  }

  boolean isComputed() {
    subgroups.isEmpty()
  }

  String getDisplayName() {
    I18n.instance.getString("incomeStatementSection.${name()}")
  }

  @Override
  String toString() {
    displayName
  }
}
