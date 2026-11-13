package se.alipsa.accounting.domain

import se.alipsa.accounting.support.I18n

enum LegalForm {

  AKTIEBOLAG('2099'),
  ENSKILD_FIRMA('2019'),
  HANDELSBOLAG_KB('2019')

  final String defaultResultAccount

  private LegalForm(String defaultResultAccount) {
    this.defaultResultAccount = defaultResultAccount
  }

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
