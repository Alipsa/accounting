package se.alipsa.accounting.ui

import se.alipsa.accounting.domain.Account
import se.alipsa.accounting.domain.SruSignCondition
import se.alipsa.accounting.domain.SruSuggestion
import se.alipsa.accounting.domain.VatCode
import se.alipsa.accounting.service.AccountService
import se.alipsa.accounting.support.I18n

import java.awt.Component
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Insets

import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextField

/** Modal dialog for editing an account's name, classification, VAT code, and status flags. */
final class AccountEditorDialog {

  private AccountEditorDialog() {
  }

  static AccountEditorResult show(Component parent, Account account, List<SruSuggestion> sruSuggestions = []) {
    JTextField nameField = new JTextField(account.accountName, 24)
    JComboBox<String> classCombo = new JComboBox<>(ChartOfAccountsPanel.ACCOUNT_CLASSES.collect { String accountClass ->
      ChartOfAccountsPanel.displayAccountClass(accountClass)
    } as String[])
    JComboBox<String> sideCombo = new JComboBox<>(ChartOfAccountsPanel.NORMAL_BALANCE_SIDES.collect { String side ->
      ChartOfAccountsPanel.displayNormalBalanceSide(side)
    } as String[])
    JComboBox<VatCodeChoice> vatCombo = new JComboBox<>()
    JCheckBox activeCheckBox = new JCheckBox()
    activeCheckBox.selected = account.active
    JCheckBox reviewCheckBox = new JCheckBox()
    reviewCheckBox.selected = account.manualReviewRequired
    JTextField sruCodeField = new JTextField(account.sruCode, 10)
    JTextField sruCode2Field = new JTextField(account.sruCode2, 10)
    JPanel sruSuggestionPanel = buildSruSuggestionPanel(sruSuggestions, sruCodeField)

    classCombo.selectedIndex = Math.max(0, ChartOfAccountsPanel.ACCOUNT_CLASSES.indexOf(account.accountClass))
    sideCombo.selectedIndex = Math.max(0, ChartOfAccountsPanel.NORMAL_BALANCE_SIDES.indexOf(account.normalBalanceSide))
    populateVatCombo(vatCombo, selectedAccountClass(classCombo), account.vatCode)
    classCombo.addActionListener {
      populateVatCombo(vatCombo, selectedAccountClass(classCombo), null)
    }

    JPanel editorPanel = buildEditorPanel(
        nameField, classCombo, sideCombo, vatCombo, activeCheckBox, reviewCheckBox,
        sruCodeField, sruCode2Field, sruSuggestionPanel)
    int result = JOptionPane.showConfirmDialog(
        parent,
        editorPanel,
        I18n.instance.format('chartOfAccountsPanel.edit.title', account.accountNumber),
        JOptionPane.OK_CANCEL_OPTION,
        JOptionPane.PLAIN_MESSAGE
    )
    if (result != JOptionPane.OK_OPTION) {
      return null
    }

    VatCodeChoice selectedVatChoice = vatCombo.selectedItem as VatCodeChoice
    new AccountEditorResult(
        nameField.text,
        selectedAccountClass(classCombo),
        ChartOfAccountsPanel.NORMAL_BALANCE_SIDES[sideCombo.selectedIndex],
        selectedVatChoice?.vatCode,
        activeCheckBox.selected,
        reviewCheckBox.selected,
        sruCodeField.text,
        sruCode2Field.text
    )
  }

  private static String selectedAccountClass(JComboBox<String> classCombo) {
    ChartOfAccountsPanel.ACCOUNT_CLASSES[classCombo.selectedIndex]
  }

  private static void populateVatCombo(JComboBox<VatCodeChoice> vatCombo, String accountClass, String currentVatCodeValue) {
    vatCombo.removeAllItems()
    VatCodeChoice noneChoice = new VatCodeChoice(null, I18n.instance.getString('chartOfAccountsPanel.vatCode.none'))
    vatCombo.addItem(noneChoice)
    AccountService.compatibleVatCodes(new Account(accountClass: accountClass)).each { VatCode code ->
      vatCombo.addItem(new VatCodeChoice(code, code.displayName))
    }
    VatCode current = VatCode.fromDatabaseValue(currentVatCodeValue)
    vatCombo.selectedItem = choices(vatCombo).find { VatCodeChoice choice -> choice.vatCode == current } ?: noneChoice
  }

  private static List<VatCodeChoice> choices(JComboBox<VatCodeChoice> vatCombo) {
    (0..<vatCombo.itemCount).collect { int index -> vatCombo.getItemAt(index) }
  }

  private static JPanel buildEditorPanel(
      JTextField nameField,
      JComboBox<String> classCombo,
      JComboBox<String> sideCombo,
      JComboBox<VatCodeChoice> vatCombo,
      JCheckBox activeCheckBox,
      JCheckBox reviewCheckBox,
      JTextField sruCodeField,
      JTextField sruCode2Field,
      JPanel sruSuggestionPanel
  ) {
    JPanel editorPanel = new JPanel(new GridBagLayout())
    GridBagConstraints labelConstraints = new GridBagConstraints(
        0, 0, 1, 1, 0.0d, 0.0d,
        GridBagConstraints.WEST, GridBagConstraints.NONE,
        new Insets(4, 4, 4, 8), 0, 0
    )
    GridBagConstraints fieldConstraints = new GridBagConstraints(
        1, 0, 1, 1, 1.0d, 0.0d,
        GridBagConstraints.WEST, GridBagConstraints.HORIZONTAL,
        new Insets(4, 0, 4, 4), 0, 0
    )
    addRow(editorPanel, labelConstraints, fieldConstraints, 0, 'name', nameField)
    addRow(editorPanel, labelConstraints, fieldConstraints, 1, 'class', classCombo)
    addRow(editorPanel, labelConstraints, fieldConstraints, 2, 'normal', sideCombo)
    addRow(editorPanel, labelConstraints, fieldConstraints, 3, 'vatCode', vatCombo)
    addRow(editorPanel, labelConstraints, fieldConstraints, 4, 'active', activeCheckBox)
    addRow(editorPanel, labelConstraints, fieldConstraints, 5, 'review', reviewCheckBox)
    addRow(editorPanel, labelConstraints, fieldConstraints, 6, 'sruCode', sruCodeField)

    GridBagConstraints suggestionConstraints = new GridBagConstraints(
        1, 7, 1, 1, 1.0d, 0.0d,
        GridBagConstraints.WEST, GridBagConstraints.HORIZONTAL,
        new Insets(0, 0, 4, 4), 0, 0
    )
    editorPanel.add(sruSuggestionPanel, suggestionConstraints)

    addRow(editorPanel, labelConstraints, fieldConstraints, 8, 'sruCode2', sruCode2Field)

    GridBagConstraints legendConstraints = new GridBagConstraints(
        0, 9, 2, 1, 1.0d, 0.0d,
        GridBagConstraints.WEST, GridBagConstraints.HORIZONTAL,
        new Insets(12, 4, 4, 4), 0, 0
    )
    editorPanel.add(buildLegend(), legendConstraints)
    editorPanel
  }

  private static JPanel buildSruSuggestionPanel(List<SruSuggestion> suggestions, JTextField sruCodeField) {
    JPanel panel = new JPanel(new GridLayout(Math.max(suggestions.size(), 1), 1, 0, 2))
    if (!suggestions) {
      return panel
    }
    suggestions.each { SruSuggestion suggestion ->
      String text
      switch (suggestion.signCondition) {
        case SruSignCondition.NET_POSITIVE:
          text = I18n.instance.format('chartOfAccountsPanel.sru.suggestionSignPositive', suggestion.fieldCode)
          break
        case SruSignCondition.NET_NEGATIVE:
          text = I18n.instance.format('chartOfAccountsPanel.sru.suggestionSignNegative', suggestion.fieldCode)
          break
        default:
          text = I18n.instance.format('chartOfAccountsPanel.sru.suggestion', suggestion.fieldCode)
      }
      JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0))
      row.add(new JLabel(text))
      JButton useButton = new JButton(I18n.instance.getString('chartOfAccountsPanel.sru.useSuggestion'))
      useButton.addActionListener {
        sruCodeField.text = suggestion.fieldCode
      }
      row.add(useButton)
      panel.add(row)
    }
    panel
  }

  private static void addRow(
      JPanel editorPanel,
      GridBagConstraints labelConstraints,
      GridBagConstraints fieldConstraints,
      int row,
      String fieldKey,
      JComponent field
  ) {
    labelConstraints.gridy = row
    fieldConstraints.gridy = row
    JLabel label = new JLabel(I18n.instance.getString("chartOfAccountsPanel.table.${fieldKey}"))
    String description = I18n.instance.getString("chartOfAccountsPanel.edit.description.${fieldKey}")
    label.toolTipText = description
    field.toolTipText = description
    editorPanel.add(label, labelConstraints)
    editorPanel.add(field, fieldConstraints)
  }

  private static JLabel buildLegend() {
    List<String> fieldKeys = ['name', 'class', 'normal', 'vatCode', 'active', 'review', 'sruCode', 'sruCode2']
    StringBuilder html = new StringBuilder('<html>')
    fieldKeys.each { String fieldKey ->
      String label = I18n.instance.getString("chartOfAccountsPanel.table.${fieldKey}")
      String description = I18n.instance.getString("chartOfAccountsPanel.edit.description.${fieldKey}")
      html << "<b>${label}:</b> ${description}<br>"
    }
    html << '</html>'
    JLabel legend = new JLabel(html.toString())
    legend.border = BorderFactory.createEmptyBorder(4, 0, 0, 0)
    legend.font = legend.font.deriveFont(legend.font.size2D - 1.0f)
    legend
  }

  private static final class VatCodeChoice {

    final VatCode vatCode
    final String label

    VatCodeChoice(VatCode vatCode, String label) {
      this.vatCode = vatCode
      this.label = label
    }

    @Override
    String toString() {
      label
    }
  }

  static final class AccountEditorResult {

    final String accountName
    final String accountClass
    final String normalBalanceSide
    final VatCode vatCode
    final boolean active
    final boolean manualReviewRequired
    final String sruCode
    final String sruCode2

    AccountEditorResult(
        String accountName,
        String accountClass,
        String normalBalanceSide,
        VatCode vatCode,
        boolean active,
        boolean manualReviewRequired,
        String sruCode,
        String sruCode2
    ) {
      this.accountName = accountName
      this.accountClass = accountClass
      this.normalBalanceSide = normalBalanceSide
      this.vatCode = vatCode
      this.active = active
      this.manualReviewRequired = manualReviewRequired
      this.sruCode = sruCode
      this.sruCode2 = sruCode2
    }
  }
}
