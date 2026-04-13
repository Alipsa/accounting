package se.alipsa.accounting.ui

import se.alipsa.accounting.domain.Account
import se.alipsa.accounting.service.AccountService
import se.alipsa.accounting.service.ChartOfAccountsImportService
import se.alipsa.accounting.service.ChartOfAccountsImportService.ImportSummary
import se.alipsa.accounting.service.FiscalYearService
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
final class ChartOfAccountsPanel extends JPanel implements PropertyChangeListener {

  private final AccountService accountService
  private final ChartOfAccountsImportService importService
  private final FiscalYearService fiscalYearService

  private final JTextField searchField = new JTextField(18)
  private final JComboBox<String> classFilter = new JComboBox<>()
  private final JCheckBox activeOnlyCheckBox = new JCheckBox(
      I18n.instance.getString('chartOfAccountsPanel.checkbox.activeOnly'), true)
  private final JTextArea feedbackArea = new JTextArea(3, 40)
  private final JLabel overviewLabel = new JLabel('')
  private final JLabel detailsLabel = new JLabel('')
  private final AccountTableModel accountTableModel = new AccountTableModel()
  private final JTable accountTable = new JTable(accountTableModel)

  private JLabel searchLabel
  private JLabel classLabel
  private JLabel filterLabel
  private JButton importButton
  private JButton searchButton
  private JButton resetButton
  private JButton toggleActiveButton
  private JButton openingBalanceButton

  ChartOfAccountsPanel(
      AccountService accountService,
      ChartOfAccountsImportService importService,
      FiscalYearService fiscalYearService
  ) {
    this.accountService = accountService
    this.importService = importService
    this.fiscalYearService = fiscalYearService
    I18n.instance.addLocaleChangeListener(this)
    rebuildClassFilter()
    buildUi()
    reloadAccounts()
  }

  @Override
  void propertyChange(PropertyChangeEvent evt) {
    if ('locale' == evt.propertyName) {
      SwingUtilities.invokeLater { applyLocale() }
    }
  }

  private void applyLocale() {
    searchLabel.text = I18n.instance.getString('chartOfAccountsPanel.label.search')
    classLabel.text = I18n.instance.getString('chartOfAccountsPanel.label.class')
    filterLabel.text = I18n.instance.getString('chartOfAccountsPanel.label.filter')
    activeOnlyCheckBox.text = I18n.instance.getString('chartOfAccountsPanel.checkbox.activeOnly')
    importButton.text = I18n.instance.getString('chartOfAccountsPanel.button.import')
    searchButton.text = I18n.instance.getString('chartOfAccountsPanel.button.refresh')
    resetButton.text = I18n.instance.getString('chartOfAccountsPanel.button.reset')
    toggleActiveButton.text = I18n.instance.getString('chartOfAccountsPanel.button.toggleActive')
    openingBalanceButton.text = I18n.instance.getString('chartOfAccountsPanel.button.openingBalance')
    rebuildClassFilter()
    accountTableModel.fireTableStructureChanged()
    refreshOverview()
    refreshSelectedAccountDetails()
  }

  private void rebuildClassFilter() {
    int selectedIndex = classFilter.selectedIndex
    classFilter.removeAllItems()
    String allLabel = I18n.instance.getString('chartOfAccountsPanel.filter.all')
    String reviewLabel = I18n.instance.getString('chartOfAccountsPanel.filter.reviewRequired')
    [allLabel, 'ASSET', 'LIABILITY', 'EQUITY', 'INCOME', 'EXPENSE', reviewLabel].each { String item ->
      classFilter.addItem(item)
    }
    if (selectedIndex >= 0 && selectedIndex < classFilter.itemCount) {
      classFilter.selectedIndex = selectedIndex
    }
  }

  private String allFilterLabel() {
    I18n.instance.getString('chartOfAccountsPanel.filter.all')
  }

  private String reviewFilterLabel() {
    I18n.instance.getString('chartOfAccountsPanel.filter.reviewRequired')
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
    outerPanel.add(buildFilterPanel(), BorderLayout.CENTER)
    outerPanel.add(buildActionPanel(), BorderLayout.SOUTH)
    outerPanel
  }

  private JPanel buildFilterPanel() {
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

    searchLabel = new JLabel(I18n.instance.getString('chartOfAccountsPanel.label.search'))
    filterPanel.add(searchLabel, labelConstraints)
    filterPanel.add(searchField, fieldConstraints)

    labelConstraints.gridx = 2
    fieldConstraints.gridx = 3
    classLabel = new JLabel(I18n.instance.getString('chartOfAccountsPanel.label.class'))
    filterPanel.add(classLabel, labelConstraints)
    filterPanel.add(classFilter, fieldConstraints)

    labelConstraints.gridx = 4
    fieldConstraints.gridx = 5
    filterLabel = new JLabel(I18n.instance.getString('chartOfAccountsPanel.label.filter'))
    filterPanel.add(filterLabel, labelConstraints)
    filterPanel.add(activeOnlyCheckBox, fieldConstraints)

    filterPanel
  }

  private JPanel buildActionPanel() {
    JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0))
    importButton = new JButton(I18n.instance.getString('chartOfAccountsPanel.button.import'))
    importButton.addActionListener { importChartOfAccounts() }
    searchButton = new JButton(I18n.instance.getString('chartOfAccountsPanel.button.refresh'))
    searchButton.addActionListener { reloadAccounts() }
    resetButton = new JButton(I18n.instance.getString('chartOfAccountsPanel.button.reset'))
    resetButton.addActionListener { resetFilters() }
    toggleActiveButton = new JButton(I18n.instance.getString('chartOfAccountsPanel.button.toggleActive'))
    toggleActiveButton.addActionListener { toggleSelectedAccountActive() }
    openingBalanceButton = new JButton(I18n.instance.getString('chartOfAccountsPanel.button.openingBalance'))
    openingBalanceButton.addActionListener { openOpeningBalanceDialog() }

    actionPanel.add(importButton)
    actionPanel.add(searchButton)
    actionPanel.add(resetButton)
    actionPanel.add(toggleActiveButton)
    actionPanel.add(openingBalanceButton)

    actionPanel
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
    boolean manualReviewOnly = selectedClass == reviewFilterLabel()
    String accountClass = selectedClass in [null, allFilterLabel(), reviewFilterLabel()] ? '' : selectedClass

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
    overviewLabel.text = I18n.instance.format('chartOfAccountsPanel.overview',
        overview.totalCount as Object, overview.activeCount as Object,
        overview.manualReviewCount as Object)
  }

  private void refreshSelectedAccountDetails() {
    Account account = selectedAccount()
    if (account == null) {
      detailsLabel.text = I18n.instance.getString('chartOfAccountsPanel.details.selectAccount')
      return
    }

    String yes = I18n.instance.getString('chartOfAccountsPanel.details.yes')
    String no = I18n.instance.getString('chartOfAccountsPanel.details.no')
    String notClassified = I18n.instance.getString('chartOfAccountsPanel.details.notClassified')
    String classValue = I18n.instance.format('chartOfAccountsPanel.details.class',
        account.accountClass ?: notClassified)
    String normalSide = I18n.instance.format('chartOfAccountsPanel.details.normalSide',
        account.normalBalanceSide ?: notClassified)
    String active = I18n.instance.format('chartOfAccountsPanel.details.active', account.active ? yes : no)
    String manualReview = I18n.instance.format('chartOfAccountsPanel.details.manualReview',
        account.manualReviewRequired ? yes : no)
    String note = account.classificationNote
        ? "<p>${escapeHtml(I18n.instance.format('chartOfAccountsPanel.details.note', account.classificationNote))}</p>"
        : ''
    detailsLabel.text = """
        <html>
        <h3>${escapeHtml(account.accountNumber)} ${escapeHtml(account.accountName)}</h3>
        <p>${escapeHtml(classValue)}</p>
        <p>${escapeHtml(normalSide)}</p>
        <p>${escapeHtml(active)}</p>
        <p>${escapeHtml(manualReview)}</p>
        ${note}
        </html>
    """.stripIndent().trim()
  }

  private void importChartOfAccounts() {
    JFileChooser chooser = new JFileChooser(defaultWorkbookDirectory())
    chooser.fileFilter = new FileNameExtensionFilter(
        I18n.instance.getString('chartOfAccountsPanel.fileFilter.excel'), 'xlsx')
    int result = chooser.showOpenDialog(this)
    if (result != JFileChooser.APPROVE_OPTION) {
      return
    }

    Path path = chooser.selectedFile.toPath()
    try {
      ImportSummary summary = importService.importFromExcel(path)
      reloadAccounts()
      showInfo(I18n.instance.format('chartOfAccountsPanel.message.importDone',
          summary.importedCount as Object, summary.createdCount as Object,
          summary.updatedCount as Object, summary.manualReviewCount as Object))
    } catch (Exception exception) {
      showError(exception.message ?: I18n.instance.getString('chartOfAccountsPanel.error.importFailed'))
    }
  }

  private void toggleSelectedAccountActive() {
    Account account = selectedAccount()
    if (account == null) {
      showError(I18n.instance.getString('chartOfAccountsPanel.error.selectAccount'))
      return
    }

    accountService.setAccountActive(account.accountNumber, !account.active)
    reloadAccounts()
    selectAccount(account.accountNumber)
    String status = account.active
        ? I18n.instance.getString('chartOfAccountsPanel.message.toggledInactive')
        : I18n.instance.getString('chartOfAccountsPanel.message.toggledActive')
    showInfo(I18n.instance.format('chartOfAccountsPanel.message.toggled', account.accountNumber, status))
  }

  private void openOpeningBalanceDialog() {
    Account account = selectedAccount()
    if (account == null) {
      showError(I18n.instance.getString('chartOfAccountsPanel.error.selectAccount'))
      return
    }
    if (!account.isBalanceAccount()) {
      showError(I18n.instance.getString('chartOfAccountsPanel.error.balanceAccountOnly'))
      return
    }

    OpeningBalanceDialog.showDialog(ownerFrame(), accountService, fiscalYearService, account, {
      showInfo(I18n.instance.format('chartOfAccountsPanel.message.openingBalanceUpdated', account.accountNumber))
    } as Runnable)
  }

  private void resetFilters() {
    searchField.text = ''
    classFilter.selectedItem = allFilterLabel()
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
    @SuppressWarnings('GetterMethodCouldBeProperty')
    int getColumnCount() {
      6
    }

    @Override
    String getColumnName(int column) {
      switch (column) {
        case 0: return I18n.instance.getString('chartOfAccountsPanel.table.account')
        case 1: return I18n.instance.getString('chartOfAccountsPanel.table.name')
        case 2: return I18n.instance.getString('chartOfAccountsPanel.table.class')
        case 3: return I18n.instance.getString('chartOfAccountsPanel.table.normal')
        case 4: return I18n.instance.getString('chartOfAccountsPanel.table.active')
        case 5: return I18n.instance.getString('chartOfAccountsPanel.table.review')
        default: return ''
      }
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
          String yes = I18n.instance.getString('chartOfAccountsPanel.details.yes')
          String no = I18n.instance.getString('chartOfAccountsPanel.details.no')
          return account.active ? yes : no
        case 5:
          String yes2 = I18n.instance.getString('chartOfAccountsPanel.details.yes')
          String no2 = I18n.instance.getString('chartOfAccountsPanel.details.no')
          return account.manualReviewRequired ? yes2 : no2
        default:
          return ''
      }
    }
  }
}
