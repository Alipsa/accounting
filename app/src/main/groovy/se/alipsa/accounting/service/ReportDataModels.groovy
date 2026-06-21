package se.alipsa.accounting.service

import groovy.transform.Canonical

import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.report.IncomeStatementRow
import se.alipsa.accounting.domain.report.ReportSelection

import java.math.RoundingMode
import java.time.LocalDate

final class EffectiveSelection {

  final ReportSelection selection
  final FiscalYear fiscalYear
  final long companyId
  final LocalDate startDate
  final LocalDate endDate
  final String selectionLabel
  final Locale locale

  EffectiveSelection(
      ReportSelection selection,
      FiscalYear fiscalYear,
      long companyId,
      LocalDate startDate,
      LocalDate endDate,
      String selectionLabel,
      Locale locale
  ) {
    this.selection = new ReportSelection(
        selection.reportType,
        selection.fiscalYearId,
        selection.accountingPeriodId,
        startDate,
        endDate
    )
    this.fiscalYear = fiscalYear
    this.companyId = companyId
    this.startDate = startDate
    this.endDate = endDate
    this.selectionLabel = selectionLabel
    this.locale = locale
  }
}

@Canonical
final class PostingLine {

  Long voucherId
  LocalDate accountingDate
  String voucherNumber
  String voucherDescription
  String status
  int lineIndex
  String accountNumber
  String accountName
  String accountClass
  String normalBalanceSide
  String lineDescription
  BigDecimal debitAmount
  BigDecimal creditAmount

  BigDecimal signedAmount() {
    String safeNormalBalanceSide = normalBalanceSide?.trim()?.toUpperCase(Locale.ROOT)
    if (!safeNormalBalanceSide) {
      throw new IllegalStateException('Kontot saknar normal balanssida för rapportering.')
    }
    safeNormalBalanceSide == 'DEBIT'
        ? scale(debitAmount - creditAmount)
        : scale(creditAmount - debitAmount)
  }

  private static BigDecimal scale(BigDecimal amount) {
    (amount ?: BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)
  }
}

@Canonical
final class AccountInfo {

  Long accountId
  String accountName
  String accountClass
  String normalBalanceSide
  String accountSubgroup
}

@Canonical
final class Totals {

  static final Totals ZERO = new Totals(BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2))

  BigDecimal debitAmount
  BigDecimal creditAmount
}

@Canonical
final class AccountDetail {
  String accountNumber
  String accountName
  BigDecimal amount
}

@Canonical
final class IncomeSectionBuildResult {
  List<IncomeStatementRow> rows
  BigDecimal total
}

final class VatBucket {

  BigDecimal baseAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
  BigDecimal postedOutputVat = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
  BigDecimal postedInputVat = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
  int outputPostingCount
  int inputPostingCount
}
