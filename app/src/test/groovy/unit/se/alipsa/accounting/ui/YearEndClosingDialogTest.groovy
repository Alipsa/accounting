package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.assertEquals

import org.junit.jupiter.api.Test

import se.alipsa.accounting.service.YearEndClosingPreview
import se.alipsa.accounting.support.I18n

class YearEndClosingDialogTest {

  @Test
  void summaryDistinguishesAnnualResultFromAnExistingTransfer() {
    Locale previousLocale = I18n.instance.locale
    Locale locale = Locale.forLanguageTag('sv-SE')
    try {
      I18n.instance.locale = locale
      YearEndClosingPreview preview = new YearEndClosingPreview(
          incomeTotal: 1000.00G, expenseTotal: 300.00G, netResult: 700.00G,
          transferredResult: 400.00G, remainingResult: 300.00G, resultAccountsWithBalanceCount: 3)

      List<String> lines = YearEndClosingDialog.summaryLines(preview, locale).collect { String line ->
        line.replace('\u00a0', ' ')
      }

      assertEquals([
          'Intäkter: 1 000,00',
          'Kostnader: 300,00',
          'Årets resultat: 700,00',
          'Redan överfört (8999): 400,00',
          'Återstår att överföra: 300,00',
          'Resultatkonton med saldo: 3'
      ], lines)
    } finally {
      I18n.instance.locale = previousLocale
    }
  }
}
