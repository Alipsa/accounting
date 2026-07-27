package se.alipsa.accounting.domain

import se.alipsa.accounting.support.I18n

enum LegalForm {

  AKTIEBOLAG,
  ENSKILD_FIRMA,
  HANDELSBOLAG_KB

  String getDisplayName() {
    I18n.instance.getString("legalForm.${name()}")
  }

  @Override
  String toString() {
    displayName
  }

  static LegalForm fromDatabaseValue(String value) {
    String normalized = value?.trim()
    normalized ? valueOf(normalized) : null
  }
}
