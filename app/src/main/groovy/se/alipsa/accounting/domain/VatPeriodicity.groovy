package se.alipsa.accounting.domain

import se.alipsa.accounting.support.I18n

/**
 * Company-level VAT reporting cadence used when VAT periods are generated.
 */
enum VatPeriodicity {

  MONTHLY,
  ANNUAL

  String getDisplayName() {
    I18n.instance.getString("vatPeriodicity.${name()}")
  }

  @Override
  String toString() {
    displayName
  }

  static VatPeriodicity fromDatabaseValue(String value) {
    String normalized = value?.trim()
    normalized ? valueOf(normalized) : MONTHLY
  }
}
