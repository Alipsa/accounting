package se.alipsa.accounting.service

import static se.alipsa.accounting.service.ReportValueSupport.formatAmountLocale
import static se.alipsa.accounting.service.ReportValueSupport.scale
import static se.alipsa.accounting.service.ReportValueSupport.stringRow

import groovy.sql.Sql

import se.alipsa.accounting.domain.report.TransactionReportRow
import se.alipsa.accounting.support.I18n

/** Builds the transaction-report rows and presentation data. */
final class TransactionReportBuilder {

  private final DatabaseService databaseService
  private final VoucherService voucherService

  TransactionReportBuilder(DatabaseService databaseService, VoucherService voucherService) {
    this.databaseService = databaseService
    this.voucherService = voucherService
  }

  TransactionReportBuildResult build(EffectiveSelection effective) {
    Map<Long, List<String>> correctionsByOriginalId = voucherService.findCorrectionVoucherNumbersForFiscalYear(
        effective.selection.fiscalYearId)
    List<TransactionReportRow> rows = databaseService.withSql { Sql sql ->
      ReportSqlLoader.loadPostingLines(sql, effective.selection.fiscalYearId, effective.startDate, effective.endDate)
          .sort { PostingLine line ->
            [line.accountingDate, line.voucherNumber ?: '', line.voucherId, line.lineIndex]
          }.collect { PostingLine line ->
            new TransactionReportRow(
                line.voucherId,
                line.accountingDate,
                line.voucherNumber,
                line.accountNumber,
                line.accountName,
                line.voucherDescription,
                line.lineDescription,
                line.debitAmount,
                line.creditAmount,
                TransactionReportSupport.status(line, correctionsByOriginalId)
            )
          }
    }
    BigDecimal debitTotal = rows.sum(BigDecimal.ZERO) { TransactionReportRow row -> row.debitAmount } as BigDecimal
    BigDecimal creditTotal = rows.sum(BigDecimal.ZERO) { TransactionReportRow row -> row.creditAmount } as BigDecimal
    new TransactionReportBuildResult(
        [
            I18n.instance.format('transactionReport.summary.count', rows.size()),
            I18n.instance.format('transactionReport.summary.debitTotal', formatAmountLocale(scale(debitTotal), effective.locale)),
            I18n.instance.format('transactionReport.summary.creditTotal', formatAmountLocale(scale(creditTotal), effective.locale))
        ],
        transactionReportHeaders(),
        rows.collect { TransactionReportRow row ->
          stringRow(
              row.accountingDate.toString(),
              row.voucherNumber,
              row.accountNumber,
              row.accountName,
              row.voucherDescription,
              row.lineDescription ?: '',
              formatAmountLocale(row.debitAmount, effective.locale),
              formatAmountLocale(row.creditAmount, effective.locale),
              row.status
          )
        },
        rows.collect { TransactionReportRow row -> row.voucherId },
        [typedRows: rows, lead: I18n.instance.getString('report.transactionReport.lead')]
    )
  }

  private static List<String> transactionReportHeaders() {
    [
        I18n.instance.getString('transactionReport.column.date'),
        I18n.instance.getString('transactionReport.column.voucher'),
        I18n.instance.getString('transactionReport.column.account'),
        I18n.instance.getString('transactionReport.column.accountName'),
        I18n.instance.getString('transactionReport.column.voucherText'),
        I18n.instance.getString('transactionReport.column.lineText'),
        I18n.instance.getString('transactionReport.column.debit'),
        I18n.instance.getString('transactionReport.column.credit'),
        I18n.instance.getString('transactionReport.column.status')
    ]
  }

}
