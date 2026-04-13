package se.alipsa.accounting.domain

/**
 * Company-level VAT reporting cadence used when VAT periods are generated.
 */
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
