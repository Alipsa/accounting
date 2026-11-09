package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull

import org.junit.jupiter.api.Test

import se.alipsa.accounting.domain.Account

import javax.swing.JComboBox

final class AccountEditorDialogTest {

  static {
    System.setProperty('java.awt.headless', 'true')
  }

  @Test
  void mapsNullClassAndSideToPlaceholderIndex() {
    assertEquals(0, AccountEditorDialog.initialIndex(ChartOfAccountsPanel.ACCOUNT_CLASSES, null))
    assertEquals(0, AccountEditorDialog.initialIndex(ChartOfAccountsPanel.NORMAL_BALANCE_SIDES, null))
  }

  @Test
  void mapsUnknownValuesToPlaceholderIndex() {
    assertEquals(0, AccountEditorDialog.initialIndex(ChartOfAccountsPanel.ACCOUNT_CLASSES, 'asset'))
    assertEquals(0, AccountEditorDialog.initialIndex(ChartOfAccountsPanel.ACCOUNT_CLASSES, 'BOGUS'))
    assertEquals(0, AccountEditorDialog.initialIndex(ChartOfAccountsPanel.NORMAL_BALANCE_SIDES, 'debit'))
  }

  @Test
  void mapsKnownValuesToTheirOwnEntry() {
    assertEquals(1, AccountEditorDialog.initialIndex(ChartOfAccountsPanel.ACCOUNT_CLASSES, 'ASSET'))
    assertEquals(4, AccountEditorDialog.initialIndex(ChartOfAccountsPanel.ACCOUNT_CLASSES, 'INCOME'))
    assertEquals(2, AccountEditorDialog.initialIndex(ChartOfAccountsPanel.NORMAL_BALANCE_SIDES, 'CREDIT'))
  }

  @Test
  void placeholderIndexMapsBackToNull() {
    JComboBox<String> combo = new JComboBox<>(['(ej angiven)', 'Tillgång', 'Skuld'] as String[])
    combo.selectedIndex = 0
    assertNull(AccountEditorDialog.selectedAccountClass(combo))
    assertNull(AccountEditorDialog.selectedNormalBalanceSide(combo))
  }

  @Test
  void editorFormSelectsPlaceholderForUnclassifiedAccount() {
    Account account = new Account(
        accountNumber: '8990', accountName: 'Resultat',
        accountClass: null, normalBalanceSide: null,
        active: true, manualReviewRequired: true)

    AccountEditorDialog.EditorForm form = new AccountEditorDialog.EditorForm(account, [])

    assertEquals(0, form.classCombo.selectedIndex)
    assertEquals(0, form.sideCombo.selectedIndex)
    assertNull(AccountEditorDialog.selectedAccountClass(form.classCombo))
    assertNull(AccountEditorDialog.selectedNormalBalanceSide(form.sideCombo))
  }

  @Test
  void editorFormSelectsStoredValuesForClassifiedAccount() {
    Account account = new Account(
        accountNumber: '1910', accountName: 'Kassa',
        accountClass: 'ASSET', normalBalanceSide: 'DEBIT',
        active: true, manualReviewRequired: false)

    AccountEditorDialog.EditorForm form = new AccountEditorDialog.EditorForm(account, [])

    assertEquals(1, form.classCombo.selectedIndex)
    assertEquals(1, form.sideCombo.selectedIndex)
    assertEquals('ASSET', AccountEditorDialog.selectedAccountClass(form.classCombo))
    assertEquals('DEBIT', AccountEditorDialog.selectedNormalBalanceSide(form.sideCombo))
  }
}
