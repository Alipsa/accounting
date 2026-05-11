package unit.se.alipsa.accounting.support

import static org.junit.jupiter.api.Assertions.*

import org.junit.jupiter.api.Test

import se.alipsa.accounting.support.AmountFormatter

final class AmountFormatterTest {

  private static final Locale SV = new Locale('sv', 'SE')
  private static final Locale EN = Locale.US

  @Test
  void formatWithSwedishLocale() {
    // Loose contains checks because the exact grouping separator (thin space vs regular space)
    // varies across JVM versions and locales.
    String result = AmountFormatter.format(1234.56G, SV)
    assertTrue(result.contains('1'), "Expected formatted result to contain '1': ${result}")
    assertTrue(result.contains('234'), "Expected formatted result to contain '234': ${result}")
    assertTrue(result.contains(','), "Expected comma as decimal separator for sv-SE: ${result}")
    assertTrue(result.contains('56'), "Expected formatted result to contain '56': ${result}")
  }

  @Test
  void formatWithUsLocale() {
    assertEquals('1,234.56', AmountFormatter.format(1234.56G, EN))
  }

  @Test
  void formatZero() {
    String result = AmountFormatter.format(BigDecimal.ZERO, SV)
    assertTrue(result.contains('0'), "Expected zero to format: ${result}")
    assertTrue(result.contains(','), "Expected comma as decimal separator: ${result}")
    assertTrue(result.contains('00'), "Expected two decimal zeros: ${result}")
  }

  @Test
  void formatNull() {
    String result = AmountFormatter.format(null, EN)
    assertEquals('0.00', result)
  }

  @Test
  void formatOrEmptyReturnsEmptyForNull() {
    assertEquals('', AmountFormatter.formatOrEmpty(null, EN))
  }

  @Test
  void formatOrEmptyReturnsEmptyForZero() {
    assertEquals('', AmountFormatter.formatOrEmpty(BigDecimal.ZERO, EN))
  }

  @Test
  void formatOrEmptyReturnsFormattedForNonZero() {
    assertEquals('42.00', AmountFormatter.formatOrEmpty(42.0G, EN))
  }

  @Test
  void formatOrEmptyReturnsFormattedForNegativeNonZero() {
    assertEquals('-42.00', AmountFormatter.formatOrEmpty(-42.0G, EN))
  }

  @Test
  void parseAmountSwedishComma() {
    BigDecimal result = AmountFormatter.parseAmount('1 234,56', SV)
    assertEquals(1234.56G, result)
  }

  @Test
  void parseAmountSwedishNoBreakSpace() {
    BigDecimal result = AmountFormatter.parseAmount("1${(char) 0x00A0}234,56", SV)
    assertEquals(1234.56G, result)
  }

  @Test
  void parseAmountSwedishNegative() {
    BigDecimal result = AmountFormatter.parseAmount('-1 234,56', SV)
    assertEquals(-1234.56G, result)
  }

  @Test
  void parseAmountSwedishDotFallback() {
    BigDecimal result = AmountFormatter.parseAmount('1234.56', SV)
    assertEquals(1234.56G, result)
  }

  @Test
  void parseAmountUs() {
    BigDecimal result = AmountFormatter.parseAmount('1,234.56', EN)
    assertEquals(1234.56G, result)
  }

  @Test
  void parseAmountReturnsNullForBlank() {
    assertNull(AmountFormatter.parseAmount('', EN))
    assertNull(AmountFormatter.parseAmount('   ', SV))
    assertNull(AmountFormatter.parseAmount(null, EN))
  }

  @Test
  void parseAmountOrZeroReturnsZeroForBlank() {
    assertEquals(BigDecimal.ZERO.setScale(2), AmountFormatter.parseAmountOrZero('', EN))
  }

  @Test
  void parseAmountOrZeroThrowsForInvalid() {
    assertThrows(IllegalArgumentException) {
      AmountFormatter.parseAmountOrZero('abc', EN)
    }
  }

  @Test
  void decimalSeparatorSwedish() {
    assertEquals(',' as char, AmountFormatter.decimalSeparator(SV))
  }

  @Test
  void decimalSeparatorUs() {
    assertEquals('.' as char, AmountFormatter.decimalSeparator(EN))
  }

  @Test
  void resolveLocaleValid() {
    Locale result = AmountFormatter.resolveLocale('sv-SE')
    assertEquals('sv', result.language)
    assertEquals('SE', result.country)
  }

  @Test
  void resolveLocaleNull() {
    assertEquals(Locale.forLanguageTag('sv-SE'), AmountFormatter.resolveLocale(null))
  }

  @Test
  void resolveLocaleBlank() {
    assertEquals(Locale.forLanguageTag('sv-SE'), AmountFormatter.resolveLocale(''))
  }

  @Test
  void parseAmountScalesToTwoDecimals() {
    BigDecimal result = AmountFormatter.parseAmount('42', EN)
    assertEquals(2, result.scale())
    assertEquals(42.00G, result)
  }

  @Test
  void formatEditedTreatsZeroAsEmpty() {
    assertEquals('', AmountFormatter.formatEdited('0', EN))
  }

  @Test
  void formatEditedFormatsValidSwedishAmount() {
    assertEquals(AmountFormatter.format(1234.56G, SV), AmountFormatter.formatEdited('1234,56', SV))
  }

  @Test
  void formatEditedFormatsValidUsAmount() {
    assertEquals('1,234.56', AmountFormatter.formatEdited('1234.56', EN))
  }

  @Test
  void formatEditedKeepsInvalidInput() {
    assertEquals('abc', AmountFormatter.formatEdited(' abc ', SV))
  }
}
