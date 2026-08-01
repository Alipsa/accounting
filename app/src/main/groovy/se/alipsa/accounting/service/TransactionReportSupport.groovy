package se.alipsa.accounting.service

import se.alipsa.accounting.support.I18n

/** Formats transaction-report statuses, including present-day correction markers. */
final class TransactionReportSupport {

  private TransactionReportSupport() {
  }

  static String status(PostingLine line, Map<Long, List<String>> correctionsByOriginalId) {
    List<String> correctionNumbers = correctionsByOriginalId[line.voucherId]
    if (correctionNumbers != null) {
      return I18n.instance.format('transactionReport.status.correctedBy', correctionNumbers.join(', '))
    }
    line.status == 'CORRECTION'
        ? I18n.instance.getString('transactionReport.status.correction')
        : I18n.instance.getString('transactionReport.status.active')
  }
}
