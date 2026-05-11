package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.assertEquals

import org.junit.jupiter.api.Test

import se.alipsa.accounting.domain.Account

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.function.Consumer

import javax.swing.DefaultListModel
import javax.swing.JTextField

final class AccountLookupPopupTest {

  @Test
  void autoSelectsSingleExactAccountMatchAndClearsStaleResults() {
    Account account = new Account(null, 7L, '1510', 'Kassa', 'ASSET', 'DEBIT', null, true, false, null, null)
    List<Account> selectedAccounts = []
    int callCount = 0
    long capturedCompanyId = 0L
    String capturedQuery = null
    String capturedClassFilter = 'unused'
    boolean capturedActiveOnly = false
    boolean capturedManualReviewOnly = true
    AccountLookupPopup popup = new AccountLookupPopup(
        { long companyId, String query, String classFilter, boolean activeOnly, boolean manualReviewOnly ->
          callCount++
          capturedCompanyId = companyId
          capturedQuery = query
          capturedClassFilter = classFilter
          capturedActiveOnly = activeOnly
          capturedManualReviewOnly = manualReviewOnly
          [account]
        } as AccountLookupPopup.AccountSearcher,
        7L,
        { Account selected -> selectedAccounts << selected } as Consumer<Account>
        )
    popup.attachToEditor(new JTextField('1510'))

    DefaultListModel<String> listModel = fieldValue(popup, 'listModel') as DefaultListModel<String>
    listModel.addElement('stale result')

    invokeSearch(popup)

    assertEquals([account], selectedAccounts)
    assertEquals(0, listModel.size())
    assertEquals(1, callCount)
    assertEquals(7L, capturedCompanyId)
    assertEquals('1510', capturedQuery)
    assertEquals(null, capturedClassFilter)
    assertEquals(true, capturedActiveOnly)
    assertEquals(false, capturedManualReviewOnly)
  }

  private static void invokeSearch(AccountLookupPopup popup) {
    Method searchMethod = AccountLookupPopup.getDeclaredMethod('search')
    searchMethod.accessible = true
    searchMethod.invoke(popup)
  }

  private static Object fieldValue(AccountLookupPopup popup, String fieldName) {
    Field field = AccountLookupPopup.getDeclaredField(fieldName)
    field.accessible = true
    field.get(popup)
  }
}
