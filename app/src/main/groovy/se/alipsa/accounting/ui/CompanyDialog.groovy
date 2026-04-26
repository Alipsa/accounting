package se.alipsa.accounting.ui

import se.alipsa.accounting.domain.Company
import se.alipsa.accounting.domain.VatPeriodicity
import se.alipsa.accounting.service.CompanyService
import se.alipsa.accounting.support.I18n

import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Frame
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import java.util.function.Consumer

import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities

/**
 * Modal dialog for creating and editing companies.
 */
final class CompanyDialog extends JDialog implements PropertyChangeListener {

  private static final List<String> SUPPORTED_LOCALE_TAGS = [
      'sv-SE', 'en-US', 'en-GB', 'de-DE', 'fr-FR',
      'nb-NO', 'da-DK', 'fi-FI', 'nl-NL', 'pl-PL',
      'es-ES', 'it-IT', 'pt-PT', 'pt-BR', 'cs-CZ',
      'hu-HU', 'ro-RO', 'sk-SK'
  ]

  private static final List<String> PRIORITY_CURRENCIES = [
      'SEK', 'EUR', 'USD', 'GBP', 'NOK', 'DKK', 'CHF', 'JPY', 'CNY'
  ]

  private final CompanyService companyService
  private final Consumer<Company> onSave
  private Company company

  private final JTextField companyNameField = new JTextField(28)
  private final JTextField organizationNumberField = new JTextField(18)
  private final JComboBox<String> defaultCurrencyComboBox =
      new JComboBox<>(buildCurrencyList().toArray(new String[0]))
  private final JComboBox<LocaleItem> localeComboBox =
      new JComboBox<>(buildLocaleList().toArray(new LocaleItem[0]))
  private final JComboBox<VatPeriodicity> vatPeriodicityComboBox = new JComboBox<>(VatPeriodicity.values())
  private final JTextArea validationArea = new JTextArea(4, 30)

  private JLabel companyNameLabel
  private JLabel orgNumberLabel
  private JLabel currencyLabel
  private JLabel localeLabel
  private JLabel vatPeriodLabel
  private JButton cancelButton
  private JButton saveButton

  CompanyDialog(Frame owner, CompanyService companyService, Company company, Consumer<Company> onSave) {
    super(owner, I18n.instance.getString('companyDialog.title.new'), true)
    this.companyService = companyService
    this.company = company
    this.onSave = onSave
    if (company != null) {
      title = I18n.instance.getString('companyDialog.title.edit')
    }
    I18n.instance.addLocaleChangeListener(this)
    buildUi()
    populate()
  }

  static void showDialog(Frame owner, CompanyService companyService, Company company, Consumer<Company> onSave) {
    CompanyDialog dialog = new CompanyDialog(owner, companyService, company, onSave)
    dialog.setVisible(true)
  }

  @Override
  void propertyChange(PropertyChangeEvent evt) {
    if ('locale' == evt.propertyName) {
      SwingUtilities.invokeLater { applyLocale() }
    }
  }

  @Override
  void dispose() {
    I18n.instance.removeLocaleChangeListener(this)
    super.dispose()
  }

  private void applyLocale() {
    title = company == null
        ? I18n.instance.getString('companyDialog.title.new')
        : I18n.instance.getString('companyDialog.title.edit')
    companyNameLabel.text = I18n.instance.getString('companySettingsDialog.label.companyName')
    orgNumberLabel.text = I18n.instance.getString('companySettingsDialog.label.orgNumber')
    currencyLabel.text = I18n.instance.getString('companySettingsDialog.label.currency')
    localeLabel.text = I18n.instance.getString('companySettingsDialog.label.locale')
    vatPeriodLabel.text = I18n.instance.getString('companySettingsDialog.label.vatPeriod')
    cancelButton.text = I18n.instance.getString('companySettingsDialog.button.cancel')
    saveButton.text = I18n.instance.getString('companySettingsDialog.button.save')
    pack()
  }

  private void buildUi() {
    setLayout(new BorderLayout(12, 12))
    ((JPanel) contentPane).setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12))
    add(buildValidationPanel(), BorderLayout.NORTH)
    add(buildFormPanel(), BorderLayout.CENTER)
    add(buildButtonPanel(), BorderLayout.SOUTH)
    pack()
    setResizable(false)
    setLocationRelativeTo(owner)
  }

  private JPanel buildFormPanel() {
    JPanel panel = new JPanel(new GridBagLayout())
    GridBagConstraints labelConstraints = new GridBagConstraints(
        0, 0, 1, 1, 0.0d, 0.0d,
        GridBagConstraints.WEST, GridBagConstraints.NONE,
        new Insets(4, 0, 4, 8), 0, 0
    )
    GridBagConstraints fieldConstraints = new GridBagConstraints(
        1, 0, 1, 1, 1.0d, 0.0d,
        GridBagConstraints.WEST, GridBagConstraints.HORIZONTAL,
        new Insets(4, 0, 4, 0), 0, 0
    )

    companyNameLabel = new JLabel(I18n.instance.getString('companySettingsDialog.label.companyName'))
    panel.add(companyNameLabel, labelConstraints)
    panel.add(companyNameField, fieldConstraints)

    labelConstraints.gridy = 1
    fieldConstraints.gridy = 1
    orgNumberLabel = new JLabel(I18n.instance.getString('companySettingsDialog.label.orgNumber'))
    panel.add(orgNumberLabel, labelConstraints)
    panel.add(organizationNumberField, fieldConstraints)

    labelConstraints.gridy = 2
    fieldConstraints.gridy = 2
    currencyLabel = new JLabel(I18n.instance.getString('companySettingsDialog.label.currency'))
    panel.add(currencyLabel, labelConstraints)
    panel.add(defaultCurrencyComboBox, fieldConstraints)

    labelConstraints.gridy = 3
    fieldConstraints.gridy = 3
    localeLabel = new JLabel(I18n.instance.getString('companySettingsDialog.label.locale'))
    panel.add(localeLabel, labelConstraints)
    panel.add(localeComboBox, fieldConstraints)

    labelConstraints.gridy = 4
    fieldConstraints.gridy = 4
    vatPeriodLabel = new JLabel(I18n.instance.getString('companySettingsDialog.label.vatPeriod'))
    panel.add(vatPeriodLabel, labelConstraints)
    panel.add(vatPeriodicityComboBox, fieldConstraints)

    panel
  }

  private JTextArea buildValidationPanel() {
    validationArea.setEditable(false)
    validationArea.setLineWrap(true)
    validationArea.setWrapStyleWord(true)
    validationArea.setForeground(new Color(153, 27, 27))
    validationArea.setBackground(background)
    validationArea.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0))
    validationArea.setVisible(false)
    validationArea
  }

  private JPanel buildButtonPanel() {
    JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT))
    cancelButton = new JButton(I18n.instance.getString('companySettingsDialog.button.cancel'))
    cancelButton.addActionListener { dispose() }
    saveButton = new JButton(I18n.instance.getString('companySettingsDialog.button.save'))
    saveButton.addActionListener { saveRequested() }
    panel.add(cancelButton)
    panel.add(saveButton)
    panel
  }

  private void populate() {
    if (company == null) {
      selectCurrency('SEK')
      selectLocale(Locale.getDefault().toLanguageTag())
      vatPeriodicityComboBox.selectedItem = VatPeriodicity.MONTHLY
      return
    }
    companyNameField.text = company.companyName
    organizationNumberField.text = company.organizationNumber
    selectCurrency(company.defaultCurrency ?: 'SEK')
    selectLocale(company.localeTag ?: Locale.getDefault().toLanguageTag())
    vatPeriodicityComboBox.selectedItem = company.vatPeriodicity ?: VatPeriodicity.MONTHLY
  }

  private void selectCurrency(String code) {
    for (int i = 0; i < defaultCurrencyComboBox.itemCount; i++) {
      if (defaultCurrencyComboBox.getItemAt(i) == code) {
        defaultCurrencyComboBox.selectedIndex = i
        return
      }
    }
    defaultCurrencyComboBox.addItem(code)
    defaultCurrencyComboBox.selectedItem = code
  }

  private void selectLocale(String tag) {
    for (int i = 0; i < localeComboBox.itemCount; i++) {
      if (localeComboBox.getItemAt(i).tag == tag) {
        localeComboBox.selectedIndex = i
        return
      }
    }
    LocaleItem item = new LocaleItem(tag)
    localeComboBox.addItem(item)
    localeComboBox.selectedItem = item
  }

  private void saveRequested() {
    List<ValidationMessage> messages = validateInput()
    if (!messages.isEmpty()) {
      showValidation(messages)
      return
    }

    try {
      String currency = defaultCurrencyComboBox.selectedItem as String
      String localeTag = (localeComboBox.selectedItem as LocaleItem).tag
      Company toSave = new Company(
          company?.id,
          companyNameField.text.trim(),
          organizationNumberField.text.trim(),
          currency,
          localeTag,
          vatPeriodicityComboBox.selectedItem as VatPeriodicity,
          true,
          null,
          null
      )
      company = companyService.save(toSave)
      onSave.accept(company)
      dispose()
    } catch (Exception exception) {
      showValidation([ValidationSupport.fieldError('', exception.message ?: exception.class.simpleName)])
    }
  }

  private List<ValidationMessage> validateInput() {
    List<ValidationMessage> messages = []
    if (!companyNameField.text?.trim()) {
      messages << ValidationSupport.fieldError(
          I18n.instance.getString('companySettingsDialog.label.companyName'),
          I18n.instance.getString('companySettingsDialog.error.companyNameRequired'))
    }
    if (!organizationNumberField.text?.trim()) {
      messages << ValidationSupport.fieldError(
          I18n.instance.getString('companySettingsDialog.label.orgNumber'),
          I18n.instance.getString('companySettingsDialog.error.orgNumberRequired'))
    }
    messages
  }

  private void showValidation(List<ValidationMessage> messages) {
    validationArea.text = ValidationSupport.summaryText(messages)
    validationArea.visible = true
    pack()
  }

  private static List<String> buildCurrencyList() {
    List<String> all = Currency.getAvailableCurrencies()
        .collect { Currency c -> c.currencyCode }
        .sort()
    List<String> priority = PRIORITY_CURRENCIES.findAll { String code -> all.contains(code) }
    List<String> rest = all.findAll { String code -> !priority.contains(code) }
    priority + rest
  }

  private static List<LocaleItem> buildLocaleList() {
    SUPPORTED_LOCALE_TAGS.collect { String tag -> new LocaleItem(tag) }
  }

  private static final class LocaleItem {

    final String tag
    private final String displayText

    LocaleItem(String tag) {
      this.tag = tag
      Locale locale = Locale.forLanguageTag(tag)
      this.displayText = "${tag} — ${locale.getDisplayName(locale)}"
    }

    @Override
    String toString() {
      displayText
    }
  }
}
