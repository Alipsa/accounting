package se.alipsa.accounting.ui

import se.alipsa.accounting.domain.Account
import se.alipsa.accounting.service.AccountService
import se.alipsa.accounting.service.ChartOfAccountsImportService
import se.alipsa.accounting.service.ChartOfAccountsImportService.ImportSummary
import se.alipsa.accounting.service.FiscalYearService

import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Frame
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.nio.file.Path

import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.ListSelectionEvent
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.table.AbstractTableModel

/**
 * Imports, displays and filters the chart of accounts.
 */
final class ChartOfAccountsPanel extends JPanel {

  private static final String ALL_CLASSES = 'Alla'
  private static final String REVIEW_FILTER = 'Kräver granskning'

  private final AccountService accountService
  private final ChartOfAccountsImportService importService
  private final FiscalYearService fiscalYearService

  private final JTextField searchField = new JTextField(18)
  private final JComboBox<String> classFilter = new JComboBox<>(
      [ALL_CLASSES, 'ASSET', 'LIABILITY', 'EQUITY', 'INCOME', 'EXPENSE', REVIEW_FILTER] as String[]
  )
  private final JCheckBox activeOnlyCheckBox = new JCheckBox('Endast aktiva', true)
  private final JTextArea feedbackArea = new JTextArea(3, 40)
  private final JLabel overviewLabel = new JLabel('')
  private final JLabel detailsLabel = new JLabel('')
  private final AccountTableModel accountTableModel = new AccountTableModel()
  private final JTable accountTable = new JTable(accountTableModel)

  ChartOfAccountsPanel(
      AccountService accountService,
      ChartOfAccountsImportService importService,
      FiscalYearService fiscalYearService
  ) {
    this.accountService = accountService
    this.importService = importService
    this.fiscalYearService = fiscalYearService
    buildUi()
    reloadAccounts()
  }

  private void buildUi() {
    setLayout(new BorderLayout(12, 12))
    setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16))

    add(buildToolbar(), BorderLayout.NORTH)
    add(new JScrollPane(accountTable), BorderLayout.CENTER)
    add(buildFooter(), BorderLayout.SOUTH)

    accountTable.selectionMode = ListSelectionModel.SINGLE_SELECTION
    accountTable.selectionModel.addListSelectionListener { ListSelectionEvent event ->
      if (!event.valueIsAdjusting) {
        refreshSelectedAccountDetails()
      }
    }
  }

  private JPanel buildToolbar() {
    JPanel outerPanel = new JPanel(new BorderLayout(12, 12))

    JPanel filterPanel = new JPanel(new GridBagLayout())
    GridBagConstraints labelConstraints = new GridBagConstraints(
        0, 0, 1, 1, 0.0d, 0.0d,
        GridBagConstraints.WEST, GridBagConstraints.NONE,
        new Insets(0, 0, 8, 8), 0, 0
    )
    GridBagConstraints fieldConstraints = new GridBagConstraints(
        1, 0, 1, 1, 1.0d, 0.0d,
        GridBagConstraints.WEST, GridBagConstraints.HORIZONTAL,
        new Insets(0, 0, 8, 12), 0, 0
    )

    filterPanel.add(new JLabel('Sök'), labelConstraints)
    filterPanel.add(searchField, fieldConstraints)

    labelConstraints.gridx = 2
    fieldConstraints.gridx = 3
    filterPanel.add(new JLabel('Klass'), labelConstraints)
    filterPanel.add(classFilter, fieldConstraints)

    labelConstraints.gridx = 4
    fieldConstraints.gridx = 5
    filterPanel.add(new JLabel('Filter'), labelConstraints)
    filterPanel.add(activeOnlyCheckBox, fieldConstraints)

    JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0))
    JButton importButton = new JButton('Importera BAS...')
    importButton.addActionListener { importChartOfAccounts() }
    JButton searchButton = new JButton('Uppdatera')
    searchButton.addActionListener { reloadAccounts() }
    JButton resetButton = new JButton('Nollställ')
    resetButton.addActionListener { resetFilters() }
    JButton toggleActiveButton = new JButton('Växla aktiv')
    toggleActiveButton.addActionListener { toggleSelectedAccountActive() }
    JButton openingBalanceButton = new JButton('Ingående balans...')
    openingBalanceButton.addActionListener { openOpeningBalanceDialog() }

    actionPanel.add(importButton)
    actionPanel.add(searchButton)
    actionPanel.add(resetButton)
    actionPanel.add(toggleActiveButton)
    actionPanel.add(openingBalanceButton)

    outerPanel.add(filterPanel, BorderLayout.CENTER)
    outerPanel.add(actionPanel, BorderLayout.SOUTH)
    outerPanel
  }

  private JPanel buildFooter() {
    feedbackArea.editable = false
    feedbackArea.lineWrap = true
    feedbackArea.wrapStyleWord = true
    feedbackArea.background = background

    JPanel metaPanel = new JPanel(new BorderLayout(0, 8))
    metaPanel.add(overviewLabel, BorderLayout.NORTH)
    metaPanel.add(detailsLabel, BorderLayout.CENTER)
    metaPanel.add(feedbackArea, BorderLayout.SOUTH)
    metaPanel
  }

  private void reloadAccounts() {
    String selectedClass = classFilter.selectedItem as String
    boolean manualReviewOnly = selectedClass == REVIEW_FILTER
    String accountClass = selectedClass in [null, ALL_CLASSES, REVIEW_FILTER] ? '' : selectedClass

    List<Account> accounts = accountService.searchAccounts(
        searchField.text,
        accountClass,
        activeOnlyCheckBox.selected,
        manualReviewOnly
    )
    accountTableModel.setRows(accounts)
    refreshOverview()
    refreshSelectedAccountDetails()
  }

  private void refreshOverview() {
    AccountService.AccountOverview overview = accountService.loadOverview()
    overviewLabel.text = "<html><b>Konton:</b> ${overview.totalCount} &nbsp;&nbsp; " +
        "<b>Aktiva:</b> ${overview.activeCount} &nbsp;&nbsp; " +
        "<b>Kräver granskning:</b> ${overview.manualReviewCount}</html>"
  }

  private void refreshSelectedAccountDetails() {
    Account account = selectedAccount()
    if (account == null) {
      detailsLabel.text = '<html>Välj ett konto för att visa kontoöversikt.</html>'
      return
    }

    String note = account.classificationNote ? "<p>Notering: ${escapeHtml(account.classificationNote)}</p>" : ''
    detailsLabel.text = """
        <html>
        <h3>${escapeHtml(account.accountNumber)} ${escapeHtml(account.accountName)}</h3>
        <p>Klass: ${escapeHtml(account.accountClass ?: 'Ej klassad')}</p>
        <p>Normal balanssida: ${escapeHtml(account.normalBalanceSide ?: 'Ej klassad')}</p>
        <p>Aktiv: ${account.active ? 'Ja' : 'Nej'}</p>
        <p>Manuell granskning: ${account.manualReviewRequired ? 'Ja' : 'Nej'}</p>
        ${note}
        </html>
    """.stripIndent().trim()
  }

  private void importChartOfAccounts() {
    JFileChooser chooser = new JFileChooser(defaultWorkbookDirectory())
    chooser.fileFilter = new FileNameExtensionFilter('Excel-filer (*.xlsx)', 'xlsx')
    int result = chooser.showOpenDialog(this)
    if (result != JFileChooser.APPROVE_OPTION) {
      return
    }

    Path path = chooser.selectedFile.toPath()
    try {
      ImportSummary summary = importService.importFromExcel(path)
      reloadAccounts()
      showInfo("Import klar: ${summary.importedCount} konton, ${summary.createdCount} nya, " +
          "${summary.updatedCount} uppdaterade, ${summary.manualReviewCount} kräver granskning.")
    } catch (Exception exception) {
      showError(exception.message ?: 'Kontoplanen kunde inte importeras.')
    }
  }

  private void toggleSelectedAccountActive() {
    Account account = selectedAccount()
    if (account == null) {
      showError('Välj ett konto först.')
      return
    }

    accountService.setAccountActive(account.accountNumber, !account.active)
    reloadAccounts()
    selectAccount(account.accountNumber)
    showInfo("Konto ${account.accountNumber} är nu ${account.active ? 'inaktivt' : 'aktivt'}.")
  }

  private void openOpeningBalanceDialog() {
    Account account = selectedAccount()
    if (account == null) {
      showError('Välj ett konto först.')
      return
    }
    if (!account.isBalanceAccount()) {
      showError('Ingående balans kan bara registreras på balanskonton.')
      return
    }

    OpeningBalanceDialog.showDialog(ownerFrame(), accountService, fiscalYearService, account, {
      showInfo("Ingående balans uppdaterades för konto ${account.accountNumber}.")
    } as Runnable)
  }

  private void resetFilters() {
    searchField.text = ''
    classFilter.selectedItem = ALL_CLASSES
    activeOnlyCheckBox.selected = true
    reloadAccounts()
  }

  private File defaultWorkbookDirectory() {
    File directory = new File('specs')
    directory.isDirectory() ? directory : new File('.')
  }

  private Account selectedAccount() {
    int selectedRow = accountTable.selectedRow
    selectedRow < 0 ? null : accountTableModel.rowAt(selectedRow)
  }

  private void selectAccount(String accountNumber) {
    int index = accountTableModel.indexOf(accountNumber)
    if (index >= 0) {
      accountTable.setRowSelectionInterval(index, index)
    }
  }

  private Frame ownerFrame() {
    Object window = SwingUtilities.getWindowAncestor(this)
    window instanceof Frame ? (Frame) window : null
  }

  private void showInfo(String message) {
    feedbackArea.foreground = new Color(22, 101, 52)
    feedbackArea.text = message
  }

  private void showError(String message) {
    feedbackArea.foreground = new Color(153, 27, 27)
    feedbackArea.text = message
  }

  private static String escapeHtml(String text) {
    if (text == null) {
      return ''
    }
    text
        .replace('&', '&amp;')
        .replace('<', '&lt;')
        .replace('>', '&gt;')
  }

  private static final class AccountTableModel extends AbstractTableModel {

    private static final List<String> COLUMNS = ['Konto', 'Namn', 'Klass', 'Normal', 'Aktiv', 'Granska']
    private List<Account> rows = []

    void setRows(List<Account> rows) {
      this.rows = new ArrayList<>(rows)
      fireTableDataChanged()
    }

    Account rowAt(int rowIndex) {
      rows[rowIndex]
    }

    int indexOf(String accountNumber) {
      rows.findIndexOf { Account account -> account.accountNumber == accountNumber }
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
      Account account = rows[rowIndex]
      switch (columnIndex) {
        case 0:
          return account.accountNumber
        case 1:
          return account.accountName
        case 2:
          return account.accountClass ?: ''
        case 3:
          return account.normalBalanceSide ?: ''
        case 4:
          return account.active ? 'Ja' : 'Nej'
        case 5:
          return account.manualReviewRequired ? 'Ja' : 'Nej'
        default:
          return ''
      }
    }
  }
}
