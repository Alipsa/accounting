package se.alipsa.accounting.domain

import groovy.transform.CompileStatic

/**
 * Company-level VAT reporting cadence used when VAT periods are generated.
 */
@CompileStatic
enum VatPeriodicity {

  MONTHLY('Månadsvis'),
  ANNUAL('Årsvis')

  final String label

  VatPeriodicity(String label) {
    this.label = label
  }

  static VatPeriodicity fromDatabaseValue(String value) {
    String normalized = value?.trim()
    normalized ? valueOf(normalized) : MONTHLY
  }
}
