package se.alipsa.accounting.ui

import se.alipsa.accounting.domain.AccountingPeriod
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.service.AccountingPeriodService
import se.alipsa.accounting.service.ClosingService
import se.alipsa.accounting.service.FiscalYearService
import se.alipsa.accounting.service.OpeningBalanceService
import se.alipsa.accounting.service.OpeningBalanceService.OpeningBalanceDrift
import se.alipsa.accounting.support.I18n
import se.alipsa.datepicker.DatePicker
import se.alipsa.datepicker.TextFieldPosition

import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Frame
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import java.time.LocalDate

import javax.swing.*
import javax.swing.event.ListSelectionEvent
import javax.swing.table.AbstractTableModel

/**
 * Lists fiscal years and their periods, and provides create, closing, and reopen actions.
 */
final class FiscalYearPanel extends JPanel implements PropertyChangeListener {

  private final FiscalYearService fiscalYearService
  private final AccountingPeriodService accountingPeriodService
  private final ClosingService closingService
  private final OpeningBalanceService openingBalanceService
  private final ActiveCompanyManager activeCompanyManager

  private final JTextField nameField = new JTextField(20)
  private final DatePicker startDatePicker = createDatePicker()
  private final DatePicker endDatePicker = createDatePicker()
  private final JTextArea feedbackArea = new JTextArea(3, 40)
  private final FiscalYearTableModel fiscalYearTableModel = new FiscalYearTableModel()
  private final AccountingPeriodTableModel periodTableModel = new AccountingPeriodTableModel()
  private final JTable fiscalYearTable = new JTable(fiscalYearTableModel)
  private final JTable periodTable = new JTable(periodTableModel)

  private JLabel nameLabel
  private JLabel startDateLabel
  private JLabel endDateLabel
  private JButton createButton
  private JButton closeButton
  private JButton reopenButton
  private JButton openingBalancesButton
  private final Set<Long> driftPromptedFiscalYears = [] as Set<Long>

  FiscalYearPanel(
      FiscalYearService fiscalYearService,
      AccountingPeriodService accountingPeriodService,
      ClosingService closingService,
      OpeningBalanceService openingBalanceService,
      ActiveCompanyManager activeCompanyManager
  ) {
    this.fiscalYearService = fiscalYearService
    this.accountingPeriodService = accountingPeriodService
    this.closingService = closingService
    this.openingBalanceService = openingBalanceService
    this.activeCompanyManager = activeCompanyManager
    I18n.instance.addLocaleChangeListener(this)
    activeCompanyManager.addPropertyChangeListener(this)
    buildUi()
    reloadData()
  }

  @Override
  void propertyChange(PropertyChangeEvent evt) {
    if ('locale' == evt.propertyName) {
      SwingUtilities.invokeLater { applyLocale() }
    } else if (ActiveCompanyManager.COMPANY_ID_PROPERTY == evt.propertyName) {
      SwingUtilities.invokeLater { reloadData() }
    }
  }

  private void applyLocale() {
    nameLabel.text = I18n.instance.getString('fiscalYearPanel.label.name')
    startDateLabel.text = I18n.instance.getString('fiscalYearPanel.label.startDate')
    endDateLabel.text = I18n.instance.getString('fiscalYearPanel.label.endDate')
    startDatePicker.locale = I18n.instance.locale
    endDatePicker.locale = I18n.instance.locale
    createButton.text = I18n.instance.getString('fiscalYearPanel.button.create')
    closeButton.text = I18n.instance.getString('fiscalYearPanel.button.yearEndClosing')
    reopenButton.text = I18n.instance.getString('fiscalYearPanel.button.reopen')
    openingBalancesButton.text = I18n.instance.getString('fiscalYearPanel.button.openingBalances')
    fiscalYearTable.tableHeader.repaint()
    periodTable.tableHeader.repaint()
  }

  private void buildUi() {
    setLayout(new BorderLayout(12, 12))
    setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16))

    add(buildFormPanel(), BorderLayout.NORTH)
    add(buildTables(), BorderLayout.CENTER)
    add(buildFeedbackArea(), BorderLayout.SOUTH)

    fiscalYearTable.selectionModel.addListSelectionListener { ListSelectionEvent event ->
      if (!event.valueIsAdjusting) {
        reloadPeriodsForSelection()
      }
    }
  }

  private JPanel buildFormPanel() {
    JPanel panel = new JPanel(new BorderLayout(12, 12))
    panel.add(buildInputGrid(), BorderLayout.CENTER)

    JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0))
    createButton = new JButton(I18n.instance.getString('fiscalYearPanel.button.create'))
    createButton.addActionListener { createFiscalYearRequested() }
    closeButton = new JButton(I18n.instance.getString('fiscalYearPanel.button.yearEndClosing'))
    closeButton.addActionListener { closeSelectedFiscalYear() }
    reopenButton = new JButton(I18n.instance.getString('fiscalYearPanel.button.reopen'))
    reopenButton.addActionListener { reopenSelectedFiscalYear() }
    openingBalancesButton = new JButton(I18n.instance.getString('fiscalYearPanel.button.openingBalances'))
    openingBalancesButton.addActionListener { openOpeningBalances() }
    actionPanel.add(createButton)
    actionPanel.add(closeButton)
    actionPanel.add(reopenButton)
    actionPanel.add(openingBalancesButton)
    panel.add(actionPanel, BorderLayout.SOUTH)

    panel
  }

  private JPanel buildInputGrid() {
    JPanel panel = new JPanel(new GridBagLayout())
    GridBagConstraints labelConstraints = new GridBagConstraints(
        0, 0, 1, 1, 0.0d, 0.0d,
        GridBagConstraints.WEST, GridBagConstraints.NONE,
        new Insets(0, 0, 8, 8), 0, 0
    )
    GridBagConstraints fieldConstraints = new GridBagConstraints(
        1, 0, 1, 1, 0.0d, 0.0d,
        GridBagConstraints.WEST, GridBagConstraints.NONE,
        new Insets(0, 0, 8, 0), 0, 0
    )
    GridBagConstraints fillerConstraints = new GridBagConstraints(
        2, 0, 1, 1, 1.0d, 0.0d,
        GridBagConstraints.WEST, GridBagConstraints.HORIZONTAL,
        new Insets(0, 0, 0, 0), 0, 0
    )

    nameLabel = new JLabel(I18n.instance.getString('fiscalYearPanel.label.name'))
    panel.add(nameLabel, labelConstraints)
    panel.add(nameField, fieldConstraints)
    panel.add(new JLabel(), fillerConstraints)

    labelConstraints.gridy = 1
    fieldConstraints.gridy = 1
    fillerConstraints.gridy = 1
    startDateLabel = new JLabel(I18n.instance.getString('fiscalYearPanel.label.startDate'))
    panel.add(startDateLabel, labelConstraints)
    panel.add(startDatePicker, fieldConstraints)
    panel.add(new JLabel(), fillerConstraints)

    labelConstraints.gridy = 2
    fieldConstraints.gridy = 2
    fillerConstraints.gridy = 2
    endDateLabel = new JLabel(I18n.instance.getString('fiscalYearPanel.label.endDate'))
    panel.add(endDateLabel, labelConstraints)
    panel.add(endDatePicker, fieldConstraints)
    panel.add(new JLabel(), fillerConstraints)

    panel
  }

  private JSplitPane buildTables() {
    fiscalYearTable.selectionMode = ListSelectionModel.SINGLE_SELECTION
    periodTable.selectionMode = ListSelectionModel.SINGLE_SELECTION

    JSplitPane splitPane = new JSplitPane(
        JSplitPane.VERTICAL_SPLIT,
        new JScrollPane(fiscalYearTable),
        new JScrollPane(periodTable)
    )
    splitPane.resizeWeight = 0.45d
    splitPane
  }

  private JTextArea buildFeedbackArea() {
    feedbackArea.setEditable(false)
    feedbackArea.setLineWrap(true)
    feedbackArea.setWrapStyleWord(true)
    feedbackArea.setForeground(new Color(153, 27, 27))
    feedbackArea.setBackground(background)
    feedbackArea
  }

  private void createFiscalYearRequested() {
    List<ValidationMessage> messages = []
    LocalDate startDate = startDatePicker.date
    LocalDate endDate = endDatePicker.date
    if (startDate == null) {
      messages << ValidationSupport.fieldError(
          I18n.instance.getString('fiscalYearPanel.label.startDate'),
          I18n.instance.getString('fiscalYearPanel.error.dateRequired')
      )
    }
    if (endDate == null) {
      messages << ValidationSupport.fieldError(
          I18n.instance.getString('fiscalYearPanel.label.endDate'),
          I18n.instance.getString('fiscalYearPanel.error.dateRequired')
      )
    }
    if (!messages.isEmpty()) {
      showValidation(messages)
      return
    }

    try {
      FiscalYear year = fiscalYearService.createFiscalYear(activeCompanyManager.companyId, nameField.text, startDate, endDate)
      clearInputs()
      reloadData()
      selectFiscalYear(year.id)
      maybeTransferOpeningBalances(year)
      activeCompanyManager.reloadFiscalYears()
      showInfo(I18n.instance.format('fiscalYearPanel.message.created', year.name))
    } catch (IllegalArgumentException exception) {
      showValidation([ValidationSupport.fieldError('', exception.message)])
    }
  }

  private void closeSelectedFiscalYear() {
    FiscalYear year = selectedFiscalYear()
    if (year == null) {
      showValidation([ValidationSupport.fieldError(
          '', I18n.instance.getString('fiscalYearPanel.error.selectFiscalYear')
      )])
      return
    }
    YearEndClosingDialog.showDialog(ownerFrame(), closingService, year, {
      reloadData()
      selectFiscalYear(year.id)
      showInfo(I18n.instance.format('fiscalYearPanel.message.closed', year.name))
    } as Runnable)
  }

  private void reopenSelectedFiscalYear() {
    FiscalYear year = selectedFiscalYear()
    if (year == null) {
      showValidation([ValidationSupport.fieldError(
          '', I18n.instance.getString('fiscalYearPanel.error.selectFiscalYear')
      )])
      return
    }
    if (!year.closed) {
      showValidation([ValidationSupport.fieldError(
          '', I18n.instance.format('fiscalYearPanel.error.fiscalYearNotClosed', year.name)
      )])
      return
    }
    int choice = JOptionPane.showConfirmDialog(
        this,
        I18n.instance.format('fiscalYearPanel.confirm.reopenFiscalYear', year.name),
        I18n.instance.getString('fiscalYearPanel.confirm.reopenTitle'),
        JOptionPane.YES_NO_OPTION
    )
    if (choice != JOptionPane.YES_OPTION) {
      return
    }
    FiscalYear reopened = fiscalYearService.reopenFiscalYear(year.id)
    activeCompanyManager.reloadFiscalYears()
    reloadData()
    selectFiscalYear(reopened.id)
    showInfo(I18n.instance.format('fiscalYearPanel.message.reopened', reopened.name))
  }

  private void reloadData() {
    if (!activeCompanyManager.hasActiveCompany()) {
      fiscalYearTableModel.setRows([])
      periodTableModel.setRows([])
      return
    }
    List<FiscalYear> fiscalYears = fiscalYearService.listFiscalYears(activeCompanyManager.companyId)
    fiscalYearTableModel.setRows(fiscalYears)
    if (!fiscalYears.isEmpty() && fiscalYearTable.selectedRow < 0) {
      fiscalYearTable.setRowSelectionInterval(0, 0)
    } else {
      reloadPeriodsForSelection()
    }
  }

  private void reloadPeriodsForSelection() {
    FiscalYear selected = selectedFiscalYear()
    List<AccountingPeriod> periods = selected == null
        ? []
        : accountingPeriodService.listPeriods(selected.id)
    periodTableModel.setRows(periods)
    if (selected != null) {
      maybePromptForOpeningBalanceRefresh(selected)
    }
  }

  private void selectFiscalYear(Long fiscalYearId) {
    if (fiscalYearId == null) {
      return
    }
    int index = fiscalYearTableModel.indexOf(fiscalYearId)
    if (index >= 0) {
      fiscalYearTable.setRowSelectionInterval(index, index)
    }
  }

  private FiscalYear selectedFiscalYear() {
    int selectedRow = fiscalYearTable.selectedRow
    selectedRow < 0 ? null : fiscalYearTableModel.rowAt(selectedRow)
  }

  private void clearInputs() {
    nameField.text = ''
    startDatePicker.date = null
    endDatePicker.date = null
  }

  private static DatePicker createDatePicker() {
    DatePicker picker = new DatePicker(null, null, null, I18n.instance.locale, 'yyyy-MM-dd')
    picker.textFieldPosition = TextFieldPosition.RIGHT
    picker.textField.editable = false
    picker
  }

  private Frame ownerFrame() {
    Object window = SwingUtilities.getWindowAncestor(this)
    window instanceof Frame ? (Frame) window : null
  }

  private void maybeTransferOpeningBalances(FiscalYear targetFiscalYear) {
    FiscalYear sourceFiscalYear = openingBalanceService.findImmediatePreviousFiscalYear(activeCompanyManager.companyId, targetFiscalYear.id)
    if (sourceFiscalYear == null) {
      return
    }
    int choice = JOptionPane.showConfirmDialog(
        this,
        I18n.instance.format('fiscalYearPanel.confirm.transferOpeningBalances', sourceFiscalYear.name, targetFiscalYear.name),
        I18n.instance.getString('fiscalYearPanel.confirm.transferTitle'),
        JOptionPane.YES_NO_OPTION
    )
    if (choice != JOptionPane.YES_OPTION) {
      return
    }
    int transferred = openingBalanceService.transferFromPreviousFiscalYear(sourceFiscalYear.id, targetFiscalYear.id)
    showInfo(I18n.instance.format(
        'fiscalYearPanel.message.openingBalancesTransferred',
        transferred as Object,
        sourceFiscalYear.name,
        targetFiscalYear.name
    ))
  }

  private void maybePromptForOpeningBalanceRefresh(FiscalYear fiscalYear) {
    if (!driftPromptedFiscalYears.add(fiscalYear.id)) {
      return
    }
    List<OpeningBalanceDrift> drift = openingBalanceService.detectDrift(fiscalYear.id)
    if (drift.isEmpty()) {
      return
    }
    FiscalYear sourceFiscalYear = openingBalanceService.findAutoManagedSourceFiscalYear(fiscalYear.id)
    String sourceName = sourceFiscalYear?.name ?: I18n.instance.getString('fiscalYearPanel.label.previousFiscalYearFallback')
    String promptKey = openingBalanceService.hasVoucherActivity(fiscalYear.id)
        ? 'fiscalYearPanel.confirm.refreshOpeningBalancesWithVouchers'
        : 'fiscalYearPanel.confirm.refreshOpeningBalances'
    int choice = JOptionPane.showConfirmDialog(
        this,
        I18n.instance.format(promptKey, drift.size() as Object, sourceName, fiscalYear.name),
        I18n.instance.getString('fiscalYearPanel.confirm.refreshTitle'),
        JOptionPane.YES_NO_OPTION
    )
    if (choice != JOptionPane.YES_OPTION) {
      showValidation([ValidationSupport.fieldError(
          '',
          I18n.instance.format('fiscalYearPanel.message.openingBalanceDriftDetected', drift.size() as Object, fiscalYear.name)
      )])
      return
    }
    int refreshed = openingBalanceService.refreshTransferredBalances(fiscalYear.id)
    showInfo(I18n.instance.format('fiscalYearPanel.message.openingBalancesRefreshed', refreshed as Object, fiscalYear.name))
  }

  private void openOpeningBalances() {
    FiscalYear fiscalYear = selectedFiscalYear()
    if (fiscalYear == null) {
      showValidation([ValidationSupport.fieldError(
          '',
          I18n.instance.getString('fiscalYearPanel.error.selectFiscalYear')
      )])
      return
    }
    FiscalYearOpeningBalanceDialog.showDialog(
        ownerFrame(),
        openingBalanceService,
        activeCompanyManager.companyId,
        fiscalYear,
        { driftPromptedFiscalYears.remove(fiscalYear.id) } as Runnable
    )
  }

  private void showValidation(List<ValidationMessage> messages) {
    feedbackArea.foreground = new Color(153, 27, 27)
    feedbackArea.text = ValidationSupport.summaryText(messages)
  }

  private void showInfo(String text) {
    feedbackArea.foreground = new Color(22, 101, 52)
    feedbackArea.text = text
  }

  private static final class FiscalYearTableModel extends AbstractTableModel {

    final int columnCount = 4
    private List<FiscalYear> rows = []

    void setRows(List<FiscalYear> rows) {
      this.rows = new ArrayList<>(rows)
      fireTableDataChanged()
    }

    FiscalYear rowAt(int rowIndex) {
      rows[rowIndex]
    }

    int indexOf(Long fiscalYearId) {
      rows.findIndexOf { FiscalYear year -> year.id == fiscalYearId }
    }

    @Override
    int getRowCount() {
      rows.size()
    }

    @Override
    String getColumnName(int column) {
      switch (column) {
        case 0: return I18n.instance.getString('fiscalYearPanel.table.fiscalYear.name')
        case 1: return I18n.instance.getString('fiscalYearPanel.table.fiscalYear.start')
        case 2: return I18n.instance.getString('fiscalYearPanel.table.fiscalYear.end')
        case 3: return I18n.instance.getString('fiscalYearPanel.table.fiscalYear.status')
        default: return ''
      }
    }

    @Override
    Object getValueAt(int rowIndex, int columnIndex) {
      FiscalYear year = rows[rowIndex]
      switch (columnIndex) {
        case 0:
          return year.name
        case 1:
          return year.startDate
        case 2:
          return year.endDate
        case 3:
          return year.closed
              ? I18n.instance.getString('fiscalYearPanel.table.fiscalYear.closed')
              : I18n.instance.getString('fiscalYearPanel.table.fiscalYear.open')
        default:
          return ''
      }
    }
  }

  private static final class AccountingPeriodTableModel extends AbstractTableModel {

    final int columnCount = 3
    private List<AccountingPeriod> rows = []

    void setRows(List<AccountingPeriod> rows) {
      this.rows = new ArrayList<>(rows)
      fireTableDataChanged()
    }

    AccountingPeriod rowAt(int rowIndex) {
      rows[rowIndex]
    }

    @Override
    int getRowCount() {
      rows.size()
    }

    @Override
    String getColumnName(int column) {
      switch (column) {
        case 0: return I18n.instance.getString('fiscalYearPanel.table.period.name')
        case 1: return I18n.instance.getString('fiscalYearPanel.table.period.start')
        case 2: return I18n.instance.getString('fiscalYearPanel.table.period.end')
        default: return ''
      }
    }

    @Override
    Object getValueAt(int rowIndex, int columnIndex) {
      AccountingPeriod period = rows[rowIndex]
      switch (columnIndex) {
        case 0:
          return period.periodName
        case 1:
          return period.startDate
        case 2:
          return period.endDate
        default:
          return ''
      }
    }
  }
}
