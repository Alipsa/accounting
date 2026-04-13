package se.alipsa.accounting.ui

import se.alipsa.accounting.domain.CompanySettings
import se.alipsa.accounting.domain.VatPeriodicity
import se.alipsa.accounting.service.CompanySettingsService
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

import javax.swing.*

/**
 * Modal dialog for initial company setup and later edits.
 */
final class CompanySettingsDialog extends JDialog implements PropertyChangeListener {

  private final CompanySettingsService companySettingsService
  private final Runnable onSave

  private final JTextField companyNameField = new JTextField(28)
  private final JTextField organizationNumberField = new JTextField(18)
  private final JTextField defaultCurrencyField = new JTextField(6)
  private final JTextField localeTagField = new JTextField(12)
  private final JComboBox<VatPeriodicity> vatPeriodicityComboBox = new JComboBox<>(VatPeriodicity.values())
  private final JTextArea validationArea = new JTextArea(4, 30)

  private JLabel companyNameLabel
  private JLabel orgNumberLabel
  private JLabel currencyLabel
  private JLabel localeLabel
  private JLabel vatPeriodLabel
  private JLabel languageLabel
  private JButton cancelButton
  private JButton saveButton
  private JButton englishButton
  private JButton swedishButton

  CompanySettingsDialog(Frame owner, CompanySettingsService companySettingsService, Runnable onSave) {
    super(owner, I18n.instance.getString('companySettingsDialog.title'), true)
    this.companySettingsService = companySettingsService
    this.onSave = onSave
    I18n.instance.addLocaleChangeListener(this)
    buildUi()
    populate(companySettingsService.getSettings())
  }

  static void showDialog(Frame owner, CompanySettingsService companySettingsService, Runnable onSave) {
    CompanySettingsDialog dialog = new CompanySettingsDialog(owner, companySettingsService, onSave)
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
    title = I18n.instance.getString('companySettingsDialog.title')
    companyNameLabel.text = I18n.instance.getString('companySettingsDialog.label.companyName')
    orgNumberLabel.text = I18n.instance.getString('companySettingsDialog.label.orgNumber')
    currencyLabel.text = I18n.instance.getString('companySettingsDialog.label.currency')
    localeLabel.text = I18n.instance.getString('companySettingsDialog.label.locale')
    vatPeriodLabel.text = I18n.instance.getString('companySettingsDialog.label.vatPeriod')
    languageLabel.text = I18n.instance.getString('companySettingsDialog.label.language')
    cancelButton.text = I18n.instance.getString('companySettingsDialog.button.cancel')
    saveButton.text = I18n.instance.getString('companySettingsDialog.button.save')
    pack()
  }

  private void buildUi() {
    setLayout(new BorderLayout(12, 12))
    ((JPanel) contentPane).setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12))

    add(buildFormPanel(), BorderLayout.CENTER)
    add(buildValidationPanel(), BorderLayout.NORTH)
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
    panel.add(defaultCurrencyField, fieldConstraints)

    labelConstraints.gridy = 3
    fieldConstraints.gridy = 3
    localeLabel = new JLabel(I18n.instance.getString('companySettingsDialog.label.locale'))
    panel.add(localeLabel, labelConstraints)
    panel.add(localeTagField, fieldConstraints)

    labelConstraints.gridy = 4
    fieldConstraints.gridy = 4
    vatPeriodLabel = new JLabel(I18n.instance.getString('companySettingsDialog.label.vatPeriod'))
    panel.add(vatPeriodLabel, labelConstraints)
    panel.add(vatPeriodicityComboBox, fieldConstraints)

    labelConstraints.gridy = 5
    fieldConstraints.gridy = 5
    languageLabel = new JLabel(I18n.instance.getString('companySettingsDialog.label.language'))
    panel.add(languageLabel, labelConstraints)

    JPanel languagePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0))
    englishButton = new JButton(loadFlagIcon('/icons/UK.png'))
    swedishButton = new JButton(loadFlagIcon('/icons/sweden.png'))
    englishButton.addActionListener { switchLanguage(Locale.ENGLISH) }
    swedishButton.addActionListener { switchLanguage(Locale.forLanguageTag('sv')) }
    updateLanguageButtonBorders()
    languagePanel.add(englishButton)
    languagePanel.add(swedishButton)
    panel.add(languagePanel, fieldConstraints)

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

  private void populate(CompanySettings settings) {
    if (settings == null) {
      defaultCurrencyField.text = 'SEK'
      localeTagField.text = 'sv-SE'
      vatPeriodicityComboBox.selectedItem = VatPeriodicity.MONTHLY
      return
    }
    companyNameField.text = settings.companyName
    organizationNumberField.text = settings.organizationNumber
    defaultCurrencyField.text = settings.defaultCurrency
    localeTagField.text = settings.localeTag
    vatPeriodicityComboBox.selectedItem = settings.vatPeriodicity ?: VatPeriodicity.MONTHLY
  }

  private void saveRequested() {
    List<ValidationMessage> messages = validateInput()
    if (!messages.isEmpty()) {
      showValidation(messages)
      return
    }

    CompanySettings settings = new CompanySettings(
        null,
        companyNameField.text.trim(),
        organizationNumberField.text.trim(),
        defaultCurrencyField.text.trim().toUpperCase(Locale.ROOT),
        localeTagField.text.trim(),
        vatPeriodicityComboBox.selectedItem as VatPeriodicity
    )
    companySettingsService.save(settings)
    onSave.run()
    dispose()
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
    String currency = defaultCurrencyField.text?.trim()
    if (!currency) {
      messages << ValidationSupport.fieldError(
          I18n.instance.getString('companySettingsDialog.label.currency'),
          I18n.instance.getString('companySettingsDialog.error.currencyRequired'))
    } else if (currency.length() != 3) {
      messages << ValidationSupport.fieldError(
          I18n.instance.getString('companySettingsDialog.label.currency'),
          I18n.instance.getString('companySettingsDialog.error.currencyFormat'),
          I18n.instance.getString('companySettingsDialog.error.currencyHint'))
    }
    String localeTag = localeTagField.text?.trim()
    if (!localeTag) {
      messages << ValidationSupport.fieldError(
          I18n.instance.getString('companySettingsDialog.label.locale'),
          I18n.instance.getString('companySettingsDialog.error.localeRequired'))
    } else if (Locale.forLanguageTag(localeTag).toLanguageTag() == 'und') {
      messages << ValidationSupport.fieldError(
          I18n.instance.getString('companySettingsDialog.label.locale'),
          I18n.instance.getString('companySettingsDialog.error.localeInvalid'),
          I18n.instance.getString('companySettingsDialog.error.localeHint'))
    }
    messages
  }

  private void showValidation(List<ValidationMessage> messages) {
    validationArea.text = ValidationSupport.summaryText(messages)
    validationArea.visible = true
    pack()
  }

  private void switchLanguage(Locale locale) {
    I18n.instance.setLocale(locale)
    updateLanguageButtonBorders()
  }

  private void updateLanguageButtonBorders() {
    boolean isSwedish = I18n.instance.locale.language == 'sv'
    swedishButton.border = isSwedish ?
        BorderFactory.createLoweredBevelBorder() : BorderFactory.createRaisedBevelBorder()
    englishButton.border = isSwedish ?
        BorderFactory.createRaisedBevelBorder() : BorderFactory.createLoweredBevelBorder()
  }

  private static ImageIcon loadFlagIcon(String path) {
    URL resource = CompanySettingsDialog.getResource(path)
    resource != null ? new ImageIcon(resource) : null
  }
}
