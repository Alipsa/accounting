package se.alipsa.accounting.domain.report

import groovy.transform.CompileStatic

/**
 * Supported report categories for preview, PDF generation and CSV export.
 */
@CompileStatic
enum ReportType {

  VOUCHER_LIST('Verifikationslista', 'voucher-list.ftl', true),
  GENERAL_LEDGER('Huvudbok', 'general-ledger.ftl', true),
  TRIAL_BALANCE('Provbalans', 'trial-balance.ftl', true),
  INCOME_STATEMENT('Resultatrapport', 'income-statement.ftl', false),
  BALANCE_SHEET('Balansrapport', 'balance-sheet.ftl', false),
  TRANSACTION_REPORT('Transaktionsrapport', 'transaction-report.ftl', true),
  VAT_REPORT('Momsrapport', 'vat-report.ftl', false)

  final String label
  final String templateName
  final boolean csvSupported

  private ReportType(String label, String templateName, boolean csvSupported) {
    this.label = label
    this.templateName = templateName
    this.csvSupported = csvSupported
  }

  @Override
  String toString() {
    label
  }
}
