package se.alipsa.accounting.ui

import se.alipsa.accounting.domain.CompanySettings
import se.alipsa.accounting.domain.VatPeriodicity
import se.alipsa.accounting.service.CompanySettingsService

import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Frame
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets

import javax.swing.*

/**
 * Modal dialog for initial company setup and later edits.
 */
final class CompanySettingsDialog extends JDialog {

  private final CompanySettingsService companySettingsService
  private final Runnable onSave

  private final JTextField companyNameField = new JTextField(28)
  private final JTextField organizationNumberField = new JTextField(18)
  private final JTextField defaultCurrencyField = new JTextField(6)
  private final JTextField localeTagField = new JTextField(12)
  private final JComboBox<VatPeriodicity> vatPeriodicityComboBox = new JComboBox<>(VatPeriodicity.values())
  private final JTextArea validationArea = new JTextArea(4, 30)

  CompanySettingsDialog(Frame owner, CompanySettingsService companySettingsService, Runnable onSave) {
    super(owner, 'Företagsinställningar', true)
    this.companySettingsService = companySettingsService
    this.onSave = onSave
    buildUi()
    populate(companySettingsService.getSettings())
  }

  static void showDialog(Frame owner, CompanySettingsService companySettingsService, Runnable onSave) {
    CompanySettingsDialog dialog = new CompanySettingsDialog(owner, companySettingsService, onSave)
    dialog.setVisible(true)
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

    panel.add(new JLabel('Företagsnamn'), labelConstraints)
    panel.add(companyNameField, fieldConstraints)

    labelConstraints.gridy = 1
    fieldConstraints.gridy = 1
    panel.add(new JLabel('Organisationsnummer'), labelConstraints)
    panel.add(organizationNumberField, fieldConstraints)

    labelConstraints.gridy = 2
    fieldConstraints.gridy = 2
    panel.add(new JLabel('Valuta'), labelConstraints)
    panel.add(defaultCurrencyField, fieldConstraints)

    labelConstraints.gridy = 3
    fieldConstraints.gridy = 3
    panel.add(new JLabel('Locale'), labelConstraints)
    panel.add(localeTagField, fieldConstraints)

    labelConstraints.gridy = 4
    fieldConstraints.gridy = 4
    panel.add(new JLabel('Momsperiod'), labelConstraints)
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
    JButton cancelButton = new JButton('Avbryt')
    cancelButton.addActionListener { dispose() }
    JButton saveButton = new JButton('Spara')
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
      messages << ValidationSupport.fieldError('Företagsnamn', 'måste anges')
    }
    if (!organizationNumberField.text?.trim()) {
      messages << ValidationSupport.fieldError('Organisationsnummer', 'måste anges')
    }
    String currency = defaultCurrencyField.text?.trim()
    if (!currency) {
      messages << ValidationSupport.fieldError('Valuta', 'måste anges')
    } else if (currency.length() != 3) {
      messages << ValidationSupport.fieldError('Valuta', 'ska vara en ISO-kod med tre bokstäver', 't.ex. SEK')
    }
    String localeTag = localeTagField.text?.trim()
    if (!localeTag) {
      messages << ValidationSupport.fieldError('Locale', 'måste anges')
    } else if (Locale.forLanguageTag(localeTag).toLanguageTag() == 'und') {
      messages << ValidationSupport.fieldError('Locale', 'är inte en giltig språk-tag', 't.ex. sv-SE')
    }
    messages
  }

  private void showValidation(List<ValidationMessage> messages) {
    validationArea.text = ValidationSupport.summaryText(messages)
    validationArea.visible = true
    pack()
  }
}
