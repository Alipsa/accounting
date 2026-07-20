package se.alipsa.accounting.domain

import se.alipsa.accounting.support.I18n

/**
 * Company-level method used to describe when business transactions are accounted for.
 */
enum AccountingMethod {

  CASH,
  INVOICE

  String getDisplayName() {
    I18n.instance.getString("accountingMethod.${name()}")
  }

  @Override
  String toString() {
    displayName
  }

  static AccountingMethod fromDatabaseValue(String value) {
    String normalized = value?.trim()
    normalized ? valueOf(normalized) : CASH
  }
}
