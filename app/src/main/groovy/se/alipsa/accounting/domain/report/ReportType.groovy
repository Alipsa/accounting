package se.alipsa.accounting.domain.report

import se.alipsa.accounting.support.I18n

/**
 * Supported report categories for preview and export.
 */
enum ReportType {

  VOUCHER_LIST('voucher-list.ftl', true),
  GENERAL_LEDGER('general-ledger.ftl', true),
  TRIAL_BALANCE('trial-balance.ftl', true),
  INCOME_STATEMENT('income-statement.ftl', true),
  BALANCE_SHEET('balance-sheet.ftl', false),
  TRANSACTION_REPORT('transaction-report.ftl', true),
  VAT_REPORT('vat-report.ftl', false)

  final String templateName
  final boolean csvSupported

  private ReportType(String templateName, boolean csvSupported) {
    this.templateName = templateName
    this.csvSupported = csvSupported
  }

  String getDisplayName() {
    I18n.instance.getString("reportType.${name()}")
  }

  @Override
  String toString() {
    displayName
  }
}
