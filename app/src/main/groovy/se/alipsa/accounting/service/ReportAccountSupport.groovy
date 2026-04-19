package se.alipsa.accounting.service

import se.alipsa.accounting.domain.AccountSubgroup

/**
 * Shared account-classification helpers for report generation.
 */
final class ReportAccountSupport {

  private ReportAccountSupport() {
  }

  static boolean shouldExcludeFromIncomeStatement(String accountNumber, AccountSubgroup subgroup) {
    // 8999 = "Årets resultat" (BAS): balance-sheet result transfer, not an income statement line.
    subgroup == AccountSubgroup.TAX_AND_RESULT && accountNumber == '8999'
  }

  static String inferIncomeAccountClass(AccountSubgroup subgroup) {
    switch (subgroup) {
      case AccountSubgroup.NET_REVENUE:
      case AccountSubgroup.INVOICED_COSTS:
      case AccountSubgroup.SECONDARY_INCOME:
      case AccountSubgroup.REVENUE_ADJUSTMENTS:
      case AccountSubgroup.CAPITALIZED_WORK:
      case AccountSubgroup.OTHER_OPERATING_INCOME:
      case AccountSubgroup.FINANCIAL_INCOME:
        return 'INCOME'
      case AccountSubgroup.RAW_MATERIALS:
      case AccountSubgroup.OTHER_EXTERNAL_COSTS:
      case AccountSubgroup.PERSONNEL_COSTS:
      case AccountSubgroup.DEPRECIATION:
      case AccountSubgroup.OTHER_OPERATING_COSTS:
      case AccountSubgroup.FINANCIAL_COSTS:
      case AccountSubgroup.APPROPRIATIONS:
      case AccountSubgroup.TAX_AND_RESULT:
        return 'EXPENSE'
      default:
        return null
    }
  }

  static String inferIncomeAccountClassFromAccountNumber(String accountNumber) {
    inferIncomeAccountClass(AccountSubgroup.fromAccountNumber(accountNumber))
  }

  static String inferNormalBalanceSide(String accountClass) {
    switch (accountClass) {
      case 'ASSET':
      case 'EXPENSE':
        return 'DEBIT'
      case 'LIABILITY':
      case 'EQUITY':
      case 'INCOME':
        return 'CREDIT'
      default:
        return null
    }
  }

  static String resolveSignedMovementNormalSide(
      String accountNumber,
      String storedNormalBalanceSide,
      String accountClass,
      String accountSubgroup
  ) {
    String normalized = storedNormalBalanceSide?.trim()?.toUpperCase(Locale.ROOT)
    if (normalized) {
      return normalized
    }
    normalized = inferNormalBalanceSide(accountClass?.trim()?.toUpperCase(Locale.ROOT))
    if (normalized != null) {
      return normalized
    }
    AccountSubgroup subgroup = accountSubgroup
        ? AccountSubgroup.fromDatabaseValue(accountSubgroup)
        : AccountSubgroup.fromAccountNumber(accountNumber)
    if (subgroup == null) {
      throw new IllegalStateException("Konto ${accountNumber} saknar normal balanssida för rapportering.")
    }
    // BAS groups 10-19: asset accounts with DEBIT as normal balance side.
    if (subgroup.basGroupStart >= 10 && subgroup.basGroupEnd <= 19) {
      return 'DEBIT'
    }
    // BAS groups 20-29: equity and liability accounts with CREDIT as normal balance side.
    if (subgroup.basGroupStart >= 20 && subgroup.basGroupEnd <= 29) {
      return 'CREDIT'
    }
    normalized = inferNormalBalanceSide(inferIncomeAccountClass(subgroup))
    if (normalized != null) {
      return normalized
    }
    throw new IllegalStateException("Konto ${accountNumber} saknar normal balanssida för rapportering.")
  }
}
