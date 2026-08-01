package se.alipsa.accounting.service

import se.alipsa.accounting.support.AmountFormatter

import java.math.RoundingMode

/** Shared numeric and table-value formatting for report builders. */
final class ReportValueSupport {

  private static final int AMOUNT_SCALE = 2

  private ReportValueSupport() {
  }

  static BigDecimal signedAmount(BigDecimal debitAmount, BigDecimal creditAmount, String normalBalanceSide) {
    String safeNormalBalanceSide = normalBalanceSide?.trim()?.toUpperCase(Locale.ROOT)
    if (!safeNormalBalanceSide) {
      throw new IllegalStateException('Kontot saknar normal balanssida för rapportering.')
    }
    safeNormalBalanceSide == 'DEBIT'
        ? scale(debitAmount - creditAmount)
        : scale(creditAmount - debitAmount)
  }

  static BigDecimal scale(BigDecimal amount) {
    (amount ?: BigDecimal.ZERO).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)
  }

  static String formatAmountLocale(BigDecimal amount, Locale locale) {
    AmountFormatter.format(amount, locale)
  }

  static String formatNullableAmount(BigDecimal amount, Locale locale) {
    amount == null ? '' : formatAmountLocale(amount, locale)
  }

  static List<String> stringRow(String... values) {
    values.toList()
  }
}
