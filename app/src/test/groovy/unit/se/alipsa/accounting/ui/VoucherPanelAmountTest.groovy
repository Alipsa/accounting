package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.assertEquals

import org.junit.jupiter.api.Test

import se.alipsa.accounting.support.AmountFormatter

final class VoucherPanelAmountTest {

  private static final Locale SV = new Locale('sv', 'SE')
  private static final Locale EN = Locale.US

  @Test
  void formatEditedAmountTreatsZeroAsEmpty() {
    assertEquals('', VoucherPanel.formatEditedAmount('0', SV))
  }

  @Test
  void formatEditedAmountFormatsValidSwedishAmount() {
    assertEquals(AmountFormatter.format(1234.56G, SV), VoucherPanel.formatEditedAmount('1234,56', SV))
  }

  @Test
  void formatEditedAmountFormatsValidUsAmount() {
    assertEquals('1,234.56', VoucherPanel.formatEditedAmount('1234.56', EN))
  }

  @Test
  void formatEditedAmountKeepsInvalidInput() {
    assertEquals('abc', VoucherPanel.formatEditedAmount(' abc ', SV))
  }
}
