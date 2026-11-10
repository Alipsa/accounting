package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.Test

import se.alipsa.accounting.domain.Account
import se.alipsa.accounting.domain.VatCode

import javax.swing.JComboBox

final class AccountEditorDialogTest {

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

  @Test
  void completingClassificationClearsReviewFlagOnce() {
    Account account = new Account(
        accountNumber: '8990', accountName: 'Resultat',
        active: true, manualReviewRequired: true)

    AccountEditorDialog.EditorForm form = new AccountEditorDialog.EditorForm(account, [])
    assertTrue(form.reviewCheckBox.selected)

    form.classCombo.selectedIndex = AccountEditorDialog.initialIndex(ChartOfAccountsPanel.ACCOUNT_CLASSES, 'INCOME')
    assertTrue(form.reviewCheckBox.selected, 'flag must remain until the normal side is also picked')

    form.sideCombo.selectedIndex = AccountEditorDialog.initialIndex(ChartOfAccountsPanel.NORMAL_BALANCE_SIDES, 'CREDIT')
    assertFalse(form.reviewCheckBox.selected)

    form.reviewCheckBox.selected = true
    form.classCombo.selectedIndex = AccountEditorDialog.initialIndex(ChartOfAccountsPanel.ACCOUNT_CLASSES, 'EXPENSE')
    assertTrue(form.reviewCheckBox.selected, 'the flag must not be cleared again after the user re-ticked it')
  }

  @Test
  void changingClassKeepsReviewFlagForClassifiedAccount() {
    Account account = new Account(
        accountNumber: '1910', accountName: 'Kassa',
        accountClass: 'ASSET', normalBalanceSide: 'DEBIT',
        active: true, manualReviewRequired: true)

    AccountEditorDialog.EditorForm form = new AccountEditorDialog.EditorForm(account, [])
    form.classCombo.selectedIndex = AccountEditorDialog.initialIndex(ChartOfAccountsPanel.ACCOUNT_CLASSES, 'LIABILITY')

    assertTrue(form.reviewCheckBox.selected)
  }

  @Test
  void saveRequiresBothClassAndNormalSide() {
    AccountEditorDialog.EditorForm form = new AccountEditorDialog.EditorForm(new Account(accountNumber: '8990'), [])

    assertFalse(AccountEditorDialog.isComplete(form))
    form.classCombo.selectedIndex = AccountEditorDialog.initialIndex(ChartOfAccountsPanel.ACCOUNT_CLASSES, 'INCOME')
    assertFalse(AccountEditorDialog.isComplete(form))
    form.classCombo.selectedIndex = 0
    form.sideCombo.selectedIndex = AccountEditorDialog.initialIndex(ChartOfAccountsPanel.NORMAL_BALANCE_SIDES, 'CREDIT')
    assertFalse(AccountEditorDialog.isComplete(form))
    form.classCombo.selectedIndex = AccountEditorDialog.initialIndex(ChartOfAccountsPanel.ACCOUNT_CLASSES, 'INCOME')
    assertTrue(AccountEditorDialog.isComplete(form))
  }

  @Test
  void classifyingAccountRetainsCompatibleStoredVatCode() {
    Account account = new Account(accountNumber: '8990', vatCode: 'OUTPUT_25')
    AccountEditorDialog.EditorForm form = new AccountEditorDialog.EditorForm(account, [])
    assertNull((form.vatCombo.selectedItem as AccountEditorDialog.VatCodeChoice).vatCode)

    form.classCombo.selectedIndex = AccountEditorDialog.initialIndex(ChartOfAccountsPanel.ACCOUNT_CLASSES, 'INCOME')

    assertEquals(VatCode.OUTPUT_25, (form.vatCombo.selectedItem as AccountEditorDialog.VatCodeChoice).vatCode)
  }

  @Test
  void classifyingAccountHidesStoredVatCodeUntilCompatibleClassIsSelected() {
    Account account = new Account(accountNumber: '8990', vatCode: 'OUTPUT_25')
    AccountEditorDialog.EditorForm form = new AccountEditorDialog.EditorForm(account, [])

    form.classCombo.selectedIndex = AccountEditorDialog.initialIndex(ChartOfAccountsPanel.ACCOUNT_CLASSES, 'ASSET')
    assertNull((form.vatCombo.selectedItem as AccountEditorDialog.VatCodeChoice).vatCode)

    form.classCombo.selectedIndex = AccountEditorDialog.initialIndex(ChartOfAccountsPanel.ACCOUNT_CLASSES, 'INCOME')
    assertEquals(VatCode.OUTPUT_25, (form.vatCombo.selectedItem as AccountEditorDialog.VatCodeChoice).vatCode)
  }

  @Test
  void correctingExpenseToIncomeRestoresStoredOutputVatCode() {
    Account account = new Account(accountNumber: '8990', vatCode: 'OUTPUT_25')
    AccountEditorDialog.EditorForm form = new AccountEditorDialog.EditorForm(account, [])

    form.classCombo.selectedIndex = AccountEditorDialog.initialIndex(ChartOfAccountsPanel.ACCOUNT_CLASSES, 'EXPENSE')
    assertNull((form.vatCombo.selectedItem as AccountEditorDialog.VatCodeChoice).vatCode)

    form.classCombo.selectedIndex = AccountEditorDialog.initialIndex(ChartOfAccountsPanel.ACCOUNT_CLASSES, 'INCOME')
    assertEquals(VatCode.OUTPUT_25, (form.vatCombo.selectedItem as AccountEditorDialog.VatCodeChoice).vatCode)
  }

  @Test
  void changingClassRetainsCompatibleSelectedVatCode() {
    Account account = new Account(accountNumber: '1910', accountClass: 'ASSET', vatCode: 'INPUT_25')
    AccountEditorDialog.EditorForm form = new AccountEditorDialog.EditorForm(account, [])

    form.classCombo.selectedIndex = AccountEditorDialog.initialIndex(ChartOfAccountsPanel.ACCOUNT_CLASSES, 'EXPENSE')

    assertEquals(VatCode.INPUT_25, (form.vatCombo.selectedItem as AccountEditorDialog.VatCodeChoice).vatCode)
  }

  @Test
  void changingClassDoesNotRestoreVatCodeClearedByUser() {
    Account account = new Account(accountNumber: '1910', accountClass: 'ASSET', vatCode: 'INPUT_25')
    AccountEditorDialog.EditorForm form = new AccountEditorDialog.EditorForm(account, [])
    form.vatCombo.selectedIndex = 0

    form.classCombo.selectedIndex = AccountEditorDialog.initialIndex(ChartOfAccountsPanel.ACCOUNT_CLASSES, 'EXPENSE')

    assertNull((form.vatCombo.selectedItem as AccountEditorDialog.VatCodeChoice).vatCode)
  }

  @Test
  void classifiedAccountRestoresVatCodeAfterIncompatibleClass() {
    Account account = new Account(accountNumber: '1910', accountClass: 'ASSET', vatCode: 'INPUT_25')
    AccountEditorDialog.EditorForm form = new AccountEditorDialog.EditorForm(account, [])

    form.classCombo.selectedIndex = AccountEditorDialog.initialIndex(ChartOfAccountsPanel.ACCOUNT_CLASSES, 'INCOME')
    assertNull((form.vatCombo.selectedItem as AccountEditorDialog.VatCodeChoice).vatCode)

    form.classCombo.selectedIndex = AccountEditorDialog.initialIndex(ChartOfAccountsPanel.ACCOUNT_CLASSES, 'EXPENSE')
    assertEquals(VatCode.INPUT_25, (form.vatCombo.selectedItem as AccountEditorDialog.VatCodeChoice).vatCode)
  }

  @Test
  void explicitVatClearStaysClearedAcrossIncompatibleClass() {
    Account account = new Account(accountNumber: '8990', vatCode: 'OUTPUT_25')
    AccountEditorDialog.EditorForm form = new AccountEditorDialog.EditorForm(account, [])
    form.classCombo.selectedIndex = AccountEditorDialog.initialIndex(ChartOfAccountsPanel.ACCOUNT_CLASSES, 'INCOME')
    form.vatCombo.selectedIndex = 0

    form.classCombo.selectedIndex = AccountEditorDialog.initialIndex(ChartOfAccountsPanel.ACCOUNT_CLASSES, 'EXPENSE')
    form.classCombo.selectedIndex = AccountEditorDialog.initialIndex(ChartOfAccountsPanel.ACCOUNT_CLASSES, 'INCOME')

    assertNull((form.vatCombo.selectedItem as AccountEditorDialog.VatCodeChoice).vatCode)
  }
}
