package se.alipsa.accounting.domain

enum LegalForm {

  AKTIEBOLAG,
  ENSKILD_FIRMA,
  HANDELSBOLAG_KB

  static LegalForm fromDatabaseValue(String value) {
    String normalized = value?.trim()
    normalized ? valueOf(normalized) : null
  }
}
