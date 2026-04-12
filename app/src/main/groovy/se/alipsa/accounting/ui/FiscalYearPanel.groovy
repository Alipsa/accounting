package se.alipsa.accounting.ui

import groovy.transform.CompileStatic

import se.alipsa.accounting.domain.AccountingPeriod
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.service.AccountingPeriodService
import se.alipsa.accounting.service.ClosingService
import se.alipsa.accounting.service.FiscalYearService

import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Frame
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.time.LocalDate
import java.time.format.DateTimeParseException

import javax.swing.*
import javax.swing.event.ListSelectionEvent
import javax.swing.table.AbstractTableModel

/**
 * Lists fiscal years and their periods, and provides create and lock actions.
 */
@CompileStatic
final class FiscalYearPanel extends JPanel {

  private final FiscalYearService fiscalYearService
  private final AccountingPeriodService accountingPeriodService
  private final ClosingService closingService

  private final JTextField nameField = new JTextField(20)
  private final JTextField startDateField = new JTextField(10)
  private final JTextField endDateField = new JTextField(10)
  private final JTextArea feedbackArea = new JTextArea(3, 40)
  private final FiscalYearTableModel fiscalYearTableModel = new FiscalYearTableModel()
  private final AccountingPeriodTableModel periodTableModel = new AccountingPeriodTableModel()
  private final JTable fiscalYearTable = new JTable(fiscalYearTableModel)
  private final JTable periodTable = new JTable(periodTableModel)

  FiscalYearPanel(
      FiscalYearService fiscalYearService,
      AccountingPeriodService accountingPeriodService,
      ClosingService closingService
  ) {
    this.fiscalYearService = fiscalYearService
    this.accountingPeriodService = accountingPeriodService
    this.closingService = closingService
    buildUi()
    reloadData()
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
    JButton createButton = new JButton('Skapa räkenskapsår')
    createButton.addActionListener { createFiscalYearRequested() }
    JButton closeButton = new JButton('Årsbokslut...')
    closeButton.addActionListener { closeSelectedFiscalYear() }
    JButton lockButton = new JButton('Lås vald period')
    lockButton.addActionListener { lockSelectedPeriod() }
    actionPanel.add(createButton)
    actionPanel.add(closeButton)
    actionPanel.add(lockButton)
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
        1, 0, 1, 1, 1.0d, 0.0d,
        GridBagConstraints.WEST, GridBagConstraints.HORIZONTAL,
        new Insets(0, 0, 8, 0), 0, 0
    )

    panel.add(new JLabel('Namn'), labelConstraints)
    panel.add(nameField, fieldConstraints)

    labelConstraints.gridy = 1
    fieldConstraints.gridy = 1
    panel.add(new JLabel('Startdatum'), labelConstraints)
    panel.add(startDateField, fieldConstraints)

    labelConstraints.gridy = 2
    fieldConstraints.gridy = 2
    panel.add(new JLabel('Slutdatum'), labelConstraints)
    panel.add(endDateField, fieldConstraints)

    startDateField.toolTipText = 'Ange datum som yyyy-MM-dd'
    endDateField.toolTipText = 'Ange datum som yyyy-MM-dd'
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
    LocalDate startDate = parseDate(startDateField.text, 'Startdatum', messages)
    LocalDate endDate = parseDate(endDateField.text, 'Slutdatum', messages)
    if (!messages.isEmpty()) {
      showValidation(messages)
      return
    }

    try {
      FiscalYear year = fiscalYearService.createFiscalYear(nameField.text, startDate, endDate)
      clearInputs()
      reloadData()
      selectFiscalYear(year.id)
      showInfo("Räkenskapsåret ${year.name} skapades.")
    } catch (IllegalArgumentException exception) {
      showValidation([ValidationSupport.fieldError('', exception.message)])
    }
  }

  private void closeSelectedFiscalYear() {
    FiscalYear year = selectedFiscalYear()
    if (year == null) {
      showValidation([ValidationSupport.fieldError('', 'Välj ett räkenskapsår att stänga.')])
      return
    }
    YearEndClosingDialog.showDialog(ownerFrame(), closingService, year, {
      reloadData()
      selectFiscalYear(year.id)
      showInfo("Årsbokslut genomfört för ${year.name}.")
    } as Runnable)
  }

  private void lockSelectedPeriod() {
    AccountingPeriod period = selectedPeriod()
    if (period == null) {
      showValidation([ValidationSupport.fieldError('', 'Välj en period att låsa.')])
      return
    }
    PeriodLockDialog.showDialog(ownerFrame(), accountingPeriodService, period, {
      reloadPeriodsForSelection()
      showInfo("Period ${period.periodName} är låst.")
    } as Runnable)
  }

  private void reloadData() {
    List<FiscalYear> fiscalYears = fiscalYearService.listFiscalYears()
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

  private AccountingPeriod selectedPeriod() {
    int selectedRow = periodTable.selectedRow
    selectedRow < 0 ? null : periodTableModel.rowAt(selectedRow)
  }

  private static LocalDate parseDate(String raw, String fieldName, List<ValidationMessage> messages) {
    String text = raw?.trim()
    if (!text) {
      messages << ValidationSupport.fieldError(fieldName, 'måste anges', 'format yyyy-MM-dd')
      return null
    }
    LocalDate parsedDate = null
    try {
      parsedDate = LocalDate.parse(text)
    } catch (DateTimeParseException ignored) {
      messages << ValidationSupport.fieldError(fieldName, 'har ogiltigt datumformat', 'format yyyy-MM-dd')
    }
    parsedDate
  }

  private void clearInputs() {
    nameField.text = ''
    startDateField.text = ''
    endDateField.text = ''
  }

  private Frame ownerFrame() {
    Object window = SwingUtilities.getWindowAncestor(this)
    window instanceof Frame ? (Frame) window : null
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

    private static final List<String> COLUMNS = ['Namn', 'Start', 'Slut', 'Status']
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
    int getColumnCount() {
      COLUMNS.size()
    }

    @Override
    String getColumnName(int column) {
      COLUMNS[column]
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
          return year.closed ? 'Stängt' : 'Öppet'
        default:
          return ''
      }
    }
  }

  private static final class AccountingPeriodTableModel extends AbstractTableModel {

    private static final List<String> COLUMNS = ['Period', 'Start', 'Slut', 'Låst', 'Låsorsak']
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
    int getColumnCount() {
      COLUMNS.size()
    }

    @Override
    String getColumnName(int column) {
      COLUMNS[column]
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
        case 3:
          return period.locked ? 'Ja' : 'Nej'
        case 4:
          return period.lockReason ?: ''
        default:
          return ''
      }
    }
  }
}
