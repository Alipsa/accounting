package se.alipsa.accounting.domain

import se.alipsa.accounting.support.I18n

/**
 * BAS chart of accounts subgroups mapped from the two-digit account prefix.
 */
enum AccountSubgroup {

  // Balance accounts (10-29)
  INTANGIBLE_ASSETS(10, 10),
  BUILDINGS_AND_LAND(11, 11),
  MACHINERY(12, 12),
  FINANCIAL_FIXED_ASSETS(13, 13),
  INVENTORY(14, 14),
  RECEIVABLES(15, 15),
  OTHER_CURRENT_RECEIVABLES(16, 16),
  PREPAID_EXPENSES(17, 17),
  SHORT_TERM_INVESTMENTS(18, 18),
  CASH_AND_BANK(19, 19),
  EQUITY(20, 20),
  UNTAXED_RESERVES(21, 21),
  PROVISIONS(22, 22),
  LONG_TERM_LIABILITIES(23, 23),
  SHORT_TERM_LIABILITIES_CREDIT(24, 24),
  TAX_LIABILITIES(25, 25),
  VAT_AND_EXCISE(26, 26),
  PAYROLL_TAXES(27, 27),
  OTHER_CURRENT_LIABILITIES(28, 28),
  ACCRUED_EXPENSES(29, 29),

  // Result accounts (30-89)
  NET_REVENUE(30, 34),
  INVOICED_COSTS(35, 35),
  SECONDARY_INCOME(36, 36),
  REVENUE_ADJUSTMENTS(37, 37),
  CAPITALIZED_WORK(38, 38),
  OTHER_OPERATING_INCOME(39, 39),
  RAW_MATERIALS(40, 49),
  OTHER_EXTERNAL_COSTS(50, 69),
  PERSONNEL_COSTS(70, 76),
  DEPRECIATION(77, 78),
  OTHER_OPERATING_COSTS(79, 79),
  FINANCIAL_INCOME(80, 83),
  FINANCIAL_COSTS(84, 87),
  APPROPRIATIONS(88, 88),
  TAX_AND_RESULT(89, 89)

  final int basGroupStart
  final int basGroupEnd

  AccountSubgroup(int basGroupStart, int basGroupEnd) {
    this.basGroupStart = basGroupStart
    this.basGroupEnd = basGroupEnd
  }

  boolean contains(int basGroup) {
    basGroup >= basGroupStart && basGroup <= basGroupEnd
  }

  String getDisplayName() {
    I18n.instance.getString("accountSubgroup.${name()}")
  }

  @Override
  String toString() {
    displayName
  }

  static AccountSubgroup fromAccountNumber(String accountNumber) {
    String normalized = accountNumber?.trim()
    if (!normalized || normalized.length() < 2) {
      return null
    }
    if (!normalized.substring(0, 2).matches('[0-9]{2}')) {
      return null
    }
    int prefix = Integer.parseInt(normalized.substring(0, 2))
    values().find { AccountSubgroup subgroup -> subgroup.contains(prefix) }
  }

  static AccountSubgroup fromDatabaseValue(String value) {
    String normalized = value?.trim()
    if (!normalized) {
      return null
    }
    values().find { AccountSubgroup subgroup -> subgroup.name() == normalized }
  }
}
