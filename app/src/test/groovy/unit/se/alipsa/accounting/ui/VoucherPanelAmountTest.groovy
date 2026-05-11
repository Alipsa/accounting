package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.assertEquals

import org.junit.jupiter.api.Test

final class VoucherPanelAmountTest {

  private static final Locale SV = new Locale('sv', 'SE')

  @Test
  void formatEditedAmountTreatsZeroAsEmpty() {
    assertEquals('', VoucherPanel.formatEditedAmount('0', SV))
  }
}
