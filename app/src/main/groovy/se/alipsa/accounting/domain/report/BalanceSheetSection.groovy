package se.alipsa.accounting.domain.report

import se.alipsa.accounting.domain.AccountSubgroup
import se.alipsa.accounting.support.I18n

/**
 * Logical sections of the balance sheet report, each mapping to one or more
 * {@link AccountSubgroup} values. Sections with an empty subgroup list are computed
 * total rows.
 */
enum BalanceSheetSection {

  FIXED_ASSETS([
      AccountSubgroup.INTANGIBLE_ASSETS,
      AccountSubgroup.BUILDINGS_AND_LAND,
      AccountSubgroup.MACHINERY,
      AccountSubgroup.FINANCIAL_FIXED_ASSETS
  ]),
  CURRENT_ASSETS([
      AccountSubgroup.INVENTORY,
      AccountSubgroup.RECEIVABLES,
      AccountSubgroup.OTHER_CURRENT_RECEIVABLES,
      AccountSubgroup.PREPAID_EXPENSES,
      AccountSubgroup.SHORT_TERM_INVESTMENTS,
      AccountSubgroup.CASH_AND_BANK
  ]),
  TOTAL_ASSETS([]),
  EQUITY([
      AccountSubgroup.EQUITY
  ]),
  UNTAXED_RESERVES([
      AccountSubgroup.UNTAXED_RESERVES
  ]),
  PROVISIONS([
      AccountSubgroup.PROVISIONS
  ]),
  LONG_TERM_LIABILITIES([
      AccountSubgroup.LONG_TERM_LIABILITIES
  ]),
  CURRENT_LIABILITIES([
      AccountSubgroup.SHORT_TERM_LIABILITIES_CREDIT,
      AccountSubgroup.TAX_LIABILITIES,
      AccountSubgroup.VAT_AND_EXCISE,
      AccountSubgroup.PAYROLL_TAXES,
      AccountSubgroup.OTHER_CURRENT_LIABILITIES,
      AccountSubgroup.ACCRUED_EXPENSES
  ]),
  TOTAL_EQUITY_AND_LIABILITIES([])

  final List<AccountSubgroup> subgroups

  BalanceSheetSection(List<AccountSubgroup> subgroups) {
    this.subgroups = Collections.unmodifiableList(subgroups)
  }

  boolean isComputed() {
    subgroups.isEmpty()
  }

  String getDisplayName() {
    I18n.instance.getString("balanceSheetSection.${name()}")
  }

  @Override
  String toString() {
    displayName
  }
}
