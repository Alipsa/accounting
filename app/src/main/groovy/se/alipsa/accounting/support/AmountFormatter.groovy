package se.alipsa.accounting.support

import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat

/**
 * Locale-aware formatting and parsing of monetary amounts.
 */
final class AmountFormatter {

  private static final int SCALE = 2

  @SuppressWarnings('UnnecessaryCast')
  private static final ThreadLocal<Map<Locale, DecimalFormat>> FORMATTERS =
      ThreadLocal.withInitial { [:] as Map<Locale, DecimalFormat> }

  private AmountFormatter() {}

  static String format(BigDecimal amount, Locale locale) {
    formatter(locale).format(scale(amount))
  }

  static String formatOrEmpty(BigDecimal amount, Locale locale) {
    if (amount == null || amount == BigDecimal.ZERO) {
      return ''
    }
    format(amount, locale)
  }

  static BigDecimal parseAmount(String text, Locale locale) {
    String trimmed = text?.trim()
    if (!trimmed) {
      return null
    }
    DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(locale)
    char groupSep = symbols.groupingSeparator
    char decSep = symbols.decimalSeparator
    String normalized = trimmed
    normalized = normalized.replace(String.valueOf(groupSep), '')
    if (Character.isWhitespace(groupSep) || Character.isSpaceChar(groupSep)) {
      normalized = normalized.replace(' ', '')
      normalized = normalized.replace(String.valueOf((char) 0x00A0), '')
    }
    // For sv-SE, '.' is not a grouping separator, so plain decimal-dot input reaches BigDecimal unchanged.
    normalized = normalized.replace(String.valueOf(decSep), '.')
    if (decSep != '.' as char && normalized.count('.') > 1) {
      throw new IllegalArgumentException("Ogiltigt belopp: ${text}")
    }
    new BigDecimal(normalized).setScale(SCALE, RoundingMode.HALF_UP)
  }

  static BigDecimal parseAmountOrZero(String text, Locale locale) {
    BigDecimal result = parseAmount(text, locale)
    result != null ? result : BigDecimal.ZERO.setScale(SCALE)
  }

  static char decimalSeparator(Locale locale) {
    DecimalFormatSymbols.getInstance(locale).decimalSeparator
  }

  static Locale resolveLocale(String localeTag) {
    if (!localeTag) {
      return Locale.ROOT
    }
    Locale resolved = Locale.forLanguageTag(localeTag)
    resolved.language ? resolved : Locale.ROOT
  }

  private static DecimalFormat formatter(Locale locale) {
    FORMATTERS.get().computeIfAbsent(locale) { Locale loc ->
      DecimalFormat fmt = (DecimalFormat) NumberFormat.getNumberInstance(loc)
      fmt.minimumFractionDigits = SCALE
      fmt.maximumFractionDigits = SCALE
      DecimalFormatSymbols symbols = fmt.decimalFormatSymbols
      symbols.minusSign = (char) '-'
      fmt.decimalFormatSymbols = symbols
      fmt
    }
  }

  private static BigDecimal scale(BigDecimal amount) {
    (amount ?: BigDecimal.ZERO).setScale(SCALE, RoundingMode.HALF_UP)
  }
}
