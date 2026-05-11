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
    AccountLookupPopup popup = createPopup('1510', [account], selectedAccounts)

    DefaultListModel<String> listModel = fieldValue(popup, 'listModel') as DefaultListModel<String>
    listModel.addElement('stale result')

    invokeSearch(popup)

    assertEquals([account], selectedAccounts)
    assertEquals(0, listModel.size())
  }

  @Test
  void keepsSinglePartialAccountMatchInResultList() {
    Account account = new Account(null, 7L, '1510', 'Kassa', 'ASSET', 'DEBIT', null, true, false, null, null)
    List<Account> selectedAccounts = []
    AccountLookupPopup popup = createPopup('151', [account], selectedAccounts)

    invokeSearch(popup)

    DefaultListModel<String> listModel = fieldValue(popup, 'listModel') as DefaultListModel<String>
    assertEquals([], selectedAccounts)
    assertEquals(1, listModel.size())
  }

  @Test
  void keepsMultipleAccountMatchesInResultList() {
    Account account = new Account(null, 7L, '1510', 'Kassa', 'ASSET', 'DEBIT', null, true, false, null, null)
    Account otherAccount = new Account(null, 7L, '1511', 'Bank', 'ASSET', 'DEBIT', null, true, false, null, null)
    List<Account> selectedAccounts = []
    AccountLookupPopup popup = createPopup('1510', [account, otherAccount], selectedAccounts)

    invokeSearch(popup)

    DefaultListModel<String> listModel = fieldValue(popup, 'listModel') as DefaultListModel<String>
    assertEquals([], selectedAccounts)
    assertEquals(2, listModel.size())
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

  private static AccountLookupPopup createPopup(String query, List<Account> results, List<Account> selectedAccounts) {
    AccountLookupPopup popup = new AccountLookupPopup(
        { long companyId, String searchQuery, String classFilter, boolean activeOnly, boolean manualReviewOnly -> results }
            as AccountLookupPopup.AccountSearcher,
        7L,
        { Account selected -> selectedAccounts << selected } as Consumer<Account>
        )
    popup.attachToEditor(new JTextField(query))
    popup
  }
}
