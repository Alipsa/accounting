package se.alipsa.accounting.ui

import groovy.transform.PackageScope

import com.formdev.flatlaf.util.SystemFileChooser
import com.formdev.flatlaf.util.UIScale

import se.alipsa.accounting.domain.Account
import se.alipsa.accounting.domain.AttachmentMetadata
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.Voucher
import se.alipsa.accounting.domain.VoucherLine
import se.alipsa.accounting.domain.VoucherSeries
import se.alipsa.accounting.domain.VoucherStatus
import se.alipsa.accounting.mcp.VoucherDraftAccess
import se.alipsa.accounting.service.AccountService
import se.alipsa.accounting.service.AccountingPeriodService
import se.alipsa.accounting.service.AttachmentService
import se.alipsa.accounting.service.AuditLogService
import se.alipsa.accounting.service.VoucherService
import se.alipsa.accounting.support.AmountFormatter
import se.alipsa.accounting.support.I18n
import se.alipsa.datepicker.DatePicker
import se.alipsa.datepicker.TextFieldPosition

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Desktop
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.print.PrinterException
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import java.text.MessageFormat
import java.time.LocalDate
import java.util.function.Consumer
import java.util.logging.Logger

import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.DefaultCellEditor
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.TableModelEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * Inline voucher editor panel with sequential navigation.
 * Replaces VoucherListPanel and VoucherEditor.
 */
// codenarc-disable ClassSize
final class VoucherPanel extends JPanel implements PropertyChangeListener, VoucherDraftAccess {

  private static final Logger log = Logger.getLogger(VoucherPanel.name)
  private static final Icon SAVE_ICON = new VoucherSaveIcon()

  private final VoucherService voucherService
  private final AccountService accountService
  private final AccountingPeriodService accountingPeriodService
  private final AttachmentService attachmentService
  private final AuditLogService auditLogService
  private final ActiveCompanyManager activeCompanyManager
  private final VoucherBalanceCachePreloader voucherBalanceCachePreloader

  @PackageScope
  Closure cursorMover = { int row, int col -> moveCursorToCell(row, col) }

  @PackageScope
  Closure dateFocusRequester = { datePicker.requestFocusInWindow() }

  private final JLabel voucherNumberLabel = new JLabel('')
  private final DatePicker datePicker = createDatePicker()
  private final JTextField descriptionField = new JTextField(30)
  private final JTextField seriesField = new JTextField(4)
  private final JLabel correctsLabel = new JLabel('')
  private final JLabel totalsLabel = new JLabel('')
  private final JTextArea feedbackArea = new JTextArea(2, 40)
  private final JTextField jumpField = new JTextField(8)

  private JButton prevButton
  private JButton nextButton
  private JButton saveButton
  private JButton printButton
  private JButton duplicateButton
  private JButton correctionButton
  private JButton voidButton
  private JButton addAttachmentButton
  private JButton openAttachmentButton
  private JTabbedPane tabs

  @PackageScope
  LineTableModel lineTableModel
  private JTable lineTable
  private final AttachmentTableModel attachmentTableModel = new AttachmentTableModel()
  private final JTable attachmentTable = new JTable(attachmentTableModel)
  private final AuditLogTableModel auditLogTableModel = new AuditLogTableModel()
  private final JTable auditLogTable = new JTable(auditLogTableModel)

  private List<Voucher> voucherList = []
  private int currentIndex = -1
  private Voucher currentVoucher
  private boolean readOnly = false
  private final Map<String, BigDecimal> balanceCache = [:]
  private final Map<Long, Map<String, BigDecimal>> voucherBalanceCache = [:]
  private int voucherBalanceCacheGeneration = 0

  VoucherPanel(
      VoucherService voucherService,
      AccountService accountService,
      AccountingPeriodService accountingPeriodService,
      AttachmentService attachmentService,
      AuditLogService auditLogService,
      ActiveCompanyManager activeCompanyManager
  ) {
    this.voucherService = voucherService
    this.accountService = accountService
    this.accountingPeriodService = accountingPeriodService
    this.attachmentService = attachmentService
    this.auditLogService = auditLogService
    this.activeCompanyManager = activeCompanyManager
    this.voucherBalanceCachePreloader = new VoucherBalanceCachePreloader(accountService)
    lineTableModel = new LineTableModel()
    lineTable = new JTable(lineTableModel)
    I18n.instance.addLocaleChangeListener(this)
    activeCompanyManager.addPropertyChangeListener(this)
    buildUi()
    reloadVoucherList()
  }

  @Override
  void propertyChange(PropertyChangeEvent event) {
    if (event.propertyName == ActiveCompanyManager.COMPANY_ID_PROPERTY) {
      SwingUtilities.invokeLater {
        installAccountLookupEditor()
        installAmountAndTextEditors()
        reloadVoucherList()
      }
    } else if (event.propertyName == ActiveCompanyManager.FISCAL_YEAR_PROPERTY) {
      SwingUtilities.invokeLater { reloadVoucherList() }
    } else if ('locale' == event.propertyName) {
      SwingUtilities.invokeLater { updateLabels() }
    }
  }

  private void buildUi() {
    setLayout(new BorderLayout(8, 8))
    setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12))
    add(buildHeaderBar(), BorderLayout.NORTH)
    JPanel center = new JPanel(new BorderLayout(0, 8))
    center.add(buildNavigationToolbar(), BorderLayout.NORTH)
    center.add(buildMainTabs(), BorderLayout.CENTER)
    add(center, BorderLayout.CENTER)
    add(buildFooter(), BorderLayout.SOUTH)
  }

  private JPanel buildHeaderBar() {
    JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4))
    panel.add(new JLabel(I18n.instance.getString('voucherPanel.label.voucherNumber')))
    panel.add(voucherNumberLabel)
    panel.add(new JLabel(I18n.instance.getString('voucherPanel.label.date')))
    panel.add(datePicker)
    panel.add(new JLabel(I18n.instance.getString('voucherPanel.label.description')))
    descriptionField.addActionListener { moveCursorToCell(0, 0) }
    panel.add(descriptionField)
    panel.add(new JLabel(I18n.instance.getString('voucherPanel.label.series')))
    panel.add(seriesField)
    correctsLabel.visible = false
    panel.add(correctsLabel)
    panel
  }

  private JPanel buildNavigationToolbar() {
    JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2))

    prevButton = new JButton('\u25C0')
    prevButton.toolTipText = I18n.instance.getString('voucherPanel.button.prev')
    prevButton.addActionListener { navigatePrev() }
    panel.add(prevButton)

    panel.add(new JLabel(I18n.instance.getString('voucherPanel.label.jump')))
    jumpField.addActionListener { jumpToVoucher(jumpField.text) }
    jumpField.toolTipText = I18n.instance.getString('voucherPanel.label.jump')
    panel.add(jumpField)

    nextButton = new JButton('\u25B6')
    nextButton.toolTipText = I18n.instance.getString('voucherPanel.button.next')
    nextButton.addActionListener { navigateNext() }
    panel.add(nextButton)

    saveButton = new JButton(SAVE_ICON)
    saveButton.toolTipText = I18n.instance.getString('voucherPanel.button.save')
    saveButton.addActionListener { saveVoucher() }
    panel.add(saveButton)

    printButton = new JButton('\u2399')
    printButton.toolTipText = I18n.instance.getString('voucherPanel.button.print')
    printButton.addActionListener { printCurrentVoucher() }
    panel.add(printButton)

    duplicateButton = new JButton('\u29C9')
    duplicateButton.toolTipText = I18n.instance.getString('voucherPanel.button.duplicate')
    duplicateButton.addActionListener { duplicateVoucher() }
    panel.add(duplicateButton)

    correctionButton = new JButton('\u270E')
    correctionButton.toolTipText = I18n.instance.getString('voucherPanel.button.createCorrection')
    correctionButton.addActionListener { createCorrection() }
    panel.add(correctionButton)

    voidButton = new JButton('\u2716')
    voidButton.toolTipText = I18n.instance.getString('voucherPanel.button.void')
    voidButton.addActionListener { deleteOrCancelVoucher() }
    panel.add(voidButton)

    panel
  }

  private JTabbedPane buildMainTabs() {
    tabs = new JTabbedPane()
    tabs.addTab(I18n.instance.getString('voucherPanel.tab.lines'), buildLinesTab())
    tabs.addTab(I18n.instance.getString('voucherPanel.tab.attachments'), buildAttachmentTab())
    tabs.addTab(I18n.instance.getString('voucherPanel.tab.history'), buildHistoryTab())
    tabs.addChangeListener { refreshAttachmentAndHistory() }
    tabs
  }

  private JPanel buildLinesTab() {
    lineTable.selectionMode = ListSelectionModel.SINGLE_SELECTION
    lineTable.putClientProperty('terminateEditOnFocusLost', Boolean.TRUE)
    lineTableModel.addTableModelListener { TableModelEvent event ->
      refreshTotals()
    }
    installEnterKeyNavigation()
    installTabKeyNavigation()
    installAccountLookupEditor()
    installAmountAndTextEditors()
    installRightAlignedColumns()
    configureLineTableColumnWidths()
    installDeleteKeyBinding()
    installLineTableContextMenu()

    JPanel panel = new JPanel(new BorderLayout(0, 8))
    panel.add(new JScrollPane(lineTable), BorderLayout.CENTER)
    panel
  }

  private void installRightAlignedColumns() {
    DefaultTableCellRenderer rightRenderer = new DefaultTableCellRenderer()
    rightRenderer.horizontalAlignment = SwingConstants.RIGHT
    [2, 3, 5, 6].each { int col ->
      lineTable.columnModel.getColumn(col).cellRenderer = rightRenderer
    }
  }

  private void configureLineTableColumnWidths() {
    int[] preferredWidths = [100, 500, 153, 153, 300, 230, 230]
    preferredWidths.eachWithIndex { int width, int columnIndex ->
      lineTable.columnModel.getColumn(columnIndex).preferredWidth = UIScale.scale(width)
    }
  }

  private void installDeleteKeyBinding() {
    KeyStroke deleteKey = KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0)
    KeyStroke backspaceKey = KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0)
    lineTable.getInputMap().put(deleteKey, 'deleteSelectedLine')
    lineTable.getInputMap().put(backspaceKey, 'deleteSelectedLine')
    lineTable.getActionMap().put('deleteSelectedLine', new AbstractAction() {
      @Override
      void actionPerformed(ActionEvent event) {
        if (!lineTable.editing && !readOnly) {
          removeSelectedLine()
        }
      }
    })
  }

  private void installLineTableContextMenu() {
    JPopupMenu contextMenu = new JPopupMenu()
    JMenuItem deleteItem = new JMenuItem(I18n.instance.getString('voucherPanel.button.removeLine'))
    deleteItem.addActionListener { removeSelectedLine() }
    contextMenu.add(deleteItem)
    lineTable.addMouseListener(new MouseAdapter() {
      @Override
      void mousePressed(MouseEvent event) { showContextMenu(event, contextMenu) }
      @Override
      void mouseReleased(MouseEvent event) { showContextMenu(event, contextMenu) }
    })
  }

  private void showContextMenu(MouseEvent event, JPopupMenu menu) {
    if (event.popupTrigger && !readOnly) {
      int row = lineTable.rowAtPoint(event.point)
      if (row >= 0 && row < lineTableModel.rowCount - 1) {
        lineTable.setRowSelectionInterval(row, row)
        menu.show(lineTable, event.x, event.y)
      }
    }
  }

  private void installTabKeyNavigation() {
    KeyStroke tabKey = KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0)
    lineTable.getInputMap().put(tabKey, 'tabAdvance')
    lineTable.getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        .put(tabKey, 'tabAdvance')
    lineTable.getActionMap().put('tabAdvance', new AbstractAction() {
      @Override
      void actionPerformed(ActionEvent event) {
        if (lineTable.editing) {
          int row = lineTable.editingRow
          int col = lineTable.editingColumn
          lineTable.cellEditor.stopCellEditing()
          advanceFromCell(row, col)
        } else {
          int row = lineTable.selectedRow
          int col = lineTable.selectedColumn
          if (row >= 0 && col >= 0) {
            advanceFromCell(row, col)
          }
        }
      }
    })
  }

  private void installEnterKeyNavigation() {
    KeyStroke enterKey = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)
    lineTable.getInputMap().put(enterKey, 'confirmAndAdvance')
    lineTable.getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        .put(enterKey, 'confirmAndAdvance')
    lineTable.getActionMap().put('confirmAndAdvance', new AbstractAction() {
      @Override
      void actionPerformed(ActionEvent event) {
        if (lineTable.editing) {
          int row = lineTable.editingRow
          int col = lineTable.editingColumn
          lineTable.cellEditor.stopCellEditing()
          advanceFromCell(row, col)
        } else {
          int row = lineTable.selectedRow
          int col = lineTable.selectedColumn
          if (row >= 0 && col >= 0 && lineTable.isCellEditable(row, col)) {
            lineTable.editCellAt(row, col)
          }
        }
      }
    })
  }

  // Internal keyboard-navigation hook, package-scoped for focused UI tests.
  @PackageScope
  void advanceFromCell(int row, int col) {
    switch (col) {
      case 0: // Account number — alternate from the preceding amount, otherwise use normal balance side
        LineEntry entry = lineTableModel.rows[row]
        cursorToSuggestedAmountColumn(row, entry.normalBalanceSide)
        break
      case 1: // Account description — same as account number
        LineEntry descEntry = lineTableModel.rows[row]
        cursorToSuggestedAmountColumn(row, descEntry.normalBalanceSide)
        break
      case 2: // Debet — always advance to kredit
        cursorMover.call(row, 3)
        break
      case 3: // Kredit — advance to next row account number
        ensureAutoRow()
        cursorMover.call(row + 1, 0)
        break
      case 4: // Text — next row account
        ensureAutoRow()
        cursorMover.call(row + 1, 0)
        break
      default:
        break
    }
  }

  private void moveCursorToCell(int row, int col) {
    Timer timer = new Timer(100, {
      if (row < lineTable.rowCount) {
        lineTable.requestFocusInWindow()
        lineTable.changeSelection(row, col, false, false)
        lineTable.editCellAt(row, col)
        java.awt.Component editor = lineTable.editorComponent
        if (editor != null) {
          editor.requestFocusInWindow()
        }
      }
    })
    timer.repeats = false
    timer.start()
  }

  /**
   * Suppress Enter on a cell-editor JTextField so the JTable's
   * confirmAndAdvance action handles it instead of DefaultCellEditor.
   */
  private static void suppressEditorKeys(JTextField field) {
    field.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), 'none')
    field.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), 'none')
  }

  private void installAccountLookupEditor() {
    JTextField numberEditorField = new JTextField()
    suppressEditorKeys(numberEditorField)
    Consumer<Account> onNumberSelected = { Account selected ->
      int row = lineTable.editingRow
      if (row >= 0) {
        lineTableModel.setAccountFromLookup(row, selected.accountNumber, selected.accountName,
            selected.normalBalanceSide)
        recalculateBalances(row)
        numberEditorField.text = selected.accountNumber
        if (lineTable.cellEditor != null) {
          lineTable.cellEditor.stopCellEditing()
        }
        cursorToSuggestedAmountColumn(row, selected.normalBalanceSide)
      }
    } as Consumer<Account>
    AccountLookupPopup numberPopup = new AccountLookupPopup(
        accountService, activeCompanyManager.companyId, onNumberSelected)
    DefaultCellEditor numberEditor = new VoucherLineCellEditor(numberEditorField) {
      @Override
      java.awt.Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
        java.awt.Component comp = super.getTableCellEditorComponent(table, value, isSelected, row, column)
        numberPopup.attachToEditor(numberEditorField)
        comp
      }
    }
    lineTable.columnModel.getColumn(0).cellEditor = numberEditor

    JTextField nameEditorField = new JTextField()
    suppressEditorKeys(nameEditorField)
    Consumer<Account> onNameSelected = { Account selected ->
      int row = lineTable.editingRow
      if (row >= 0) {
        lineTableModel.setAccountFromLookup(row, selected.accountNumber, selected.accountName,
            selected.normalBalanceSide)
        recalculateBalances(row)
        nameEditorField.text = selected.accountName
        if (lineTable.cellEditor != null) {
          lineTable.cellEditor.stopCellEditing()
        }
        cursorToSuggestedAmountColumn(row, selected.normalBalanceSide)
      }
    } as Consumer<Account>
    AccountLookupPopup namePopup = new AccountLookupPopup(
        accountService, activeCompanyManager.companyId, onNameSelected)
    DefaultCellEditor nameEditor = new VoucherLineCellEditor(nameEditorField) {
      @Override
      java.awt.Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
        java.awt.Component comp = super.getTableCellEditorComponent(table, value, isSelected, row, column)
        namePopup.attachToEditor(nameEditorField)
        comp
      }
    }
    lineTable.columnModel.getColumn(1).cellEditor = nameEditor
  }

  private void installAmountAndTextEditors() {
    char decSep = AmountFormatter.decimalSeparator(activeCompanyManager.companyLocale)
    [2, 3].each { int col ->
      JTextField field = new JTextField()
      field.addKeyListener(new KeyAdapter() {
        @Override
        void keyTyped(KeyEvent event) {
          char typed = event.keyChar
          if ((typed == ',' as char || typed == '.' as char) && typed != decSep) {
            event.keyChar = decSep
          }
        }
      })
      suppressEditorKeys(field)
      lineTable.columnModel.getColumn(col).cellEditor = new VoucherLineCellEditor(field)
    }
    JTextField descField = new JTextField()
    suppressEditorKeys(descField)
    lineTable.columnModel.getColumn(4).cellEditor = new VoucherLineCellEditor(descField)
  }

  private JPanel buildAttachmentTab() {
    attachmentTable.selectionMode = ListSelectionModel.SINGLE_SELECTION
    attachmentTable.selectionModel.addListSelectionListener {
      updateAttachmentButtons()
    }

    JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0))
    addAttachmentButton = new JButton(I18n.instance.getString('voucherPanel.button.addAttachment'))
    addAttachmentButton.addActionListener { addAttachmentRequested() }
    actions.add(addAttachmentButton)
    openAttachmentButton = new JButton(I18n.instance.getString('voucherPanel.button.openAttachment'))
    openAttachmentButton.addActionListener { openSelectedAttachment() }
    actions.add(openAttachmentButton)
    updateAttachmentButtons()

    JPanel panel = new JPanel(new BorderLayout(0, 8))
    panel.add(actions, BorderLayout.NORTH)
    panel.add(new JScrollPane(attachmentTable), BorderLayout.CENTER)
    panel
  }

  private JPanel buildHistoryTab() {
    auditLogTable.selectionMode = ListSelectionModel.SINGLE_SELECTION
    JPanel panel = new JPanel(new BorderLayout())
    panel.add(new JScrollPane(auditLogTable), BorderLayout.CENTER)
    panel
  }

  private JPanel buildFooter() {
    feedbackArea.editable = false
    feedbackArea.lineWrap = true
    feedbackArea.wrapStyleWord = true
    feedbackArea.opaque = false
    JPanel panel = new JPanel(new BorderLayout(0, 4))
    panel.add(totalsLabel, BorderLayout.NORTH)
    panel.add(feedbackArea, BorderLayout.CENTER)
    panel
  }

  private void reloadVoucherList() {
    voucherBalanceCache.clear()
    voucherBalanceCacheGeneration++
    FiscalYear fy = activeCompanyManager.fiscalYear
    if (fy == null || activeCompanyManager.companyId <= 0) {
      voucherList = []
      currentIndex = -1
      showBlankVoucher()
      return
    }
    // Reversed so navigatePrev() (which starts from the end) shows the most recent voucher first.
    voucherList = voucherService.listVouchers(activeCompanyManager.companyId, fy.id).reverse()
    currentIndex = -1
    showBlankVoucher()
    voucherBalanceCachePreloader.preload(
        activeCompanyManager.companyId,
        fy.id,
        voucherList,
        voucherBalanceCacheGeneration
    ) { Map<Long, Map<String, BigDecimal>> preloadedBalances, int cacheGeneration ->
      if (cacheGeneration == voucherBalanceCacheGeneration) {
        voucherBalanceCache.putAll(preloadedBalances)
      }
    }
  }

  private void showVoucher(Voucher v) {
    currentVoucher = v
    balanceCache.clear()
    if (v == null) {
      showBlankVoucher()
      return
    }
    Map<String, BigDecimal> cachedBalances = voucherBalanceCache[v.id]
    if (cachedBalances != null) {
      balanceCache.putAll(cachedBalances)
    }
    String displayNumber = v.voucherNumber ?: String.valueOf(v.id)
    voucherNumberLabel.text = displayNumber
    jumpField.text = displayNumber
    datePicker.date = v.accountingDate
    descriptionField.text = v.description ?: ''
    seriesField.text = v.seriesCode ?: 'A'
    if (v.originalVoucherId != null) {
      correctsLabel.text = I18n.instance.getString('voucherPanel.label.corrects') + ' ' + v.originalVoucherId
      correctsLabel.visible = true
    } else {
      correctsLabel.text = ''
      correctsLabel.visible = false
    }
    lineTableModel.setRows(v.lines)
    ensureAutoRow()
    recalculateAllBalances()
    clearAttachmentAndHistory()
    refreshTotals()
    applyReadOnlyState()
    updateNavigationButtons()
  }

  private void showBlankVoucher() {
    currentVoucher = null
    readOnly = false
    balanceCache.clear()
    String nextNumber = previewNextVoucherNumber('A')
    voucherNumberLabel.text = nextNumber
    jumpField.text = nextNumber
    datePicker.date = defaultDate()
    descriptionField.text = ''
    seriesField.text = 'A'
    correctsLabel.text = ''
    correctsLabel.visible = false
    lineTableModel.clear()
    lineTableModel.addBlankRows(2)
    clearAttachmentAndHistory()
    refreshTotals()
    applyReadOnlyState()
    updateNavigationButtons()
    feedbackArea.text = ''
  }

  private String previewNextVoucherNumber(String seriesCode) {
    FiscalYear fy = activeCompanyManager.fiscalYear
    if (fy == null) {
      return "${seriesCode}-1" as String
    }
    try {
      List<VoucherSeries> seriesList = voucherService.listSeries(fy.id)
      VoucherSeries series = seriesList.find { it.seriesCode == seriesCode }
      if (series != null) {
        return "${series.seriesCode}-${series.nextRunningNumber}" as String
      }
    } catch (Exception ex) {
      log.warning("Kunde inte förhandsgranska verifikatnummer: ${ex.message}")
    }
    "${seriesCode}-1" as String
  }

  private LocalDate defaultDate() {
    FiscalYear fy = activeCompanyManager.fiscalYear
    if (fy == null) {
      return LocalDate.now()
    }
    voucherService.latestVoucherDate(activeCompanyManager.companyId, fy.id) ?: fy.startDate
  }

  private void navigatePrev() {
    if (voucherList.isEmpty()) {
      return
    }
    if (currentIndex < 0) {
      currentIndex = voucherList.size() - 1
    } else if (currentIndex > 0) {
      currentIndex--
    }
    showVoucher(voucherList[currentIndex])
  }

  private void navigateNext() {
    if (voucherList.isEmpty()) {
      return
    }
    if (currentIndex < 0) {
      return
    }
    if (currentIndex >= voucherList.size() - 1) {
      currentIndex = -1
      showBlankVoucher()
    } else {
      currentIndex++
      showVoucher(voucherList[currentIndex])
    }
  }

  private void updateNavigationButtons() {
    prevButton.enabled = !voucherList.isEmpty() && currentIndex != 0
    nextButton.enabled = !voucherList.isEmpty() && currentIndex >= 0
  }

  private void jumpToVoucher(String number) {
    String normalized = number?.trim()
    if (!normalized) {
      return
    }
    FiscalYear fiscalYear = activeCompanyManager.fiscalYear
    Voucher voucher = fiscalYear == null ? null : voucherService.findVoucher(
        activeCompanyManager.companyId, fiscalYear.id, normalized)
    if (voucher != null) {
      currentIndex = voucherList.findIndexOf { Voucher candidate -> candidate.id == voucher.id }
      showVoucher(voucher)
    } else {
      showError("${I18n.instance.getString('voucherPanel.label.voucherNumber')} ${normalized} — ${I18n.instance.getString('voucherPanel.lookup.noMatches')}" as String)
    }
  }

  private void saveVoucher() {
    FiscalYear fy = activeCompanyManager.fiscalYear
    if (fy == null) {
      showError(I18n.instance.getString('voucherPanel.error.noFiscalYear'))
      return
    }
    try {
      LocalDate date = datePicker.date
      if (date == null) {
        showError(I18n.instance.getString('voucherPanel.error.dateFormat'))
        return
      }
      String description = descriptionField.text?.trim()
      List<VoucherLine> lines = lineTableModel.toVoucherLines()
      if (currentVoucher != null) {
        showError(I18n.instance.getString('voucherPanel.error.existingVoucherReadOnly'))
        return
      }
      String series = seriesField.text?.trim() ?: 'A'
      Voucher saved = voucherService.createVoucher(fy.id, series, date, description, lines)
      showInfo(I18n.instance.getString('voucherPanel.message.saved').replace('{0}', saved.voucherNumber ?: ''))
      reloadVoucherList()
    } catch (Exception ex) {
      showError(ex.message ?: I18n.instance.getString('voucherPanel.error.saveFailed'))
    }
  }

  @Override
  Map<String, Object> getVoucherDraft() {
    Map<String, Object>[] holder = new Map[1]
    runOnEdt {
      holder[0] = [
          accounting_date: datePicker.date?.toString(),
          description: descriptionField.text ?: '',
          series_code: seriesField.text?.trim() ?: 'A',
          lines: lineTableModel.toVoucherLines().collect { VoucherLine line ->
            [account_number: line.accountNumber, account_name: line.accountName, description: line.description,
             debit: line.debitAmount, credit: line.creditAmount]
          }
      ]
    }
    holder[0]
  }

  @Override
  void setVoucherDraft(Map<String, Object> draft) {
    runOnEdt {
      Object linesValue = draft.get('lines')
      if (!(linesValue instanceof List)) {
        throw new IllegalArgumentException('lines must be an array.')
      }
      List<VoucherLine> lines = []
      ((List) linesValue).eachWithIndex { Object value, int index ->
        if (!(value instanceof Map)) {
          throw new IllegalArgumentException('Each voucher line must be an object.')
        }
        Map line = (Map) value
        lines << new VoucherLine(null, null, index, null, line.account_number as String, line.account_name as String,
            line.description as String, decimal(line.debit), decimal(line.credit))
      }
      LocalDate date
      try {
        date = LocalDate.parse(draft.get('accounting_date') as String)
      } catch (Exception exception) {
        throw new IllegalArgumentException('accounting_date must be an ISO date (YYYY-MM-DD).', exception)
      }
      showBlankVoucher()
      datePicker.date = date
      descriptionField.text = draft.get('description') as String ?: ''
      seriesField.text = draft.get('series_code') as String ?: 'A'
      lineTableModel.setRows(lines)
      ensureAutoRow()
      recalculateAllBalances()
      refreshTotals()
      dateFocusRequester.call()
    }
  }

  private static BigDecimal decimal(Object value) {
    value == null || value.toString().trim().isEmpty() ? BigDecimal.ZERO : new BigDecimal(value.toString())
  }

  private static void runOnEdt(Closure action) {
    if (SwingUtilities.isEventDispatchThread()) {
      action.call()
    } else {
      try {
        SwingUtilities.invokeAndWait(action as Runnable)
      } catch (java.lang.reflect.InvocationTargetException exception) {
        Throwable cause = exception.cause
        if (cause instanceof IllegalArgumentException) {
          throw (IllegalArgumentException) cause
        }
        throw new IllegalStateException(cause?.message ?: exception.message, cause)
      }
    }
  }

  private void createCorrection() {
    if (currentVoucher == null) {
      return
    }
    try {
      Voucher correction = voucherService.createCorrectionVoucher(currentVoucher.id, null)
      showInfo(I18n.instance.format('voucherPanel.message.correctionCreated', correction.voucherNumber ?: ''))
      reloadVoucherList()
    } catch (Exception ex) {
      showError(ex.message ?: I18n.instance.getString('voucherPanel.error.correctionFailed'))
    }
  }

  private void duplicateVoucher() {
    if (currentVoucher == null) { return }
    Voucher source = currentVoucher
    String seriesCode = source.seriesCode ?: 'A'
    List<VoucherLine> copiedLines = source.lines.collect { VoucherLine line ->
      new VoucherLine(null, null, line.lineIndex, null, line.accountNumber, line.accountName, line.description, line.debitAmount ?: BigDecimal.ZERO, line.creditAmount ?: BigDecimal.ZERO)
    }
    currentIndex = -1
    showBlankVoucher()
    voucherNumberLabel.text = previewNextVoucherNumber(seriesCode)
    jumpField.text = voucherNumberLabel.text
    descriptionField.text = source.description ?: ''
    seriesField.text = seriesCode
    lineTableModel.setRows(copiedLines)
    ensureAutoRow()
    recalculateAllBalances()
    showInfo(I18n.instance.getString('voucherPanel.message.duplicateCreated'))
    dateFocusRequester.call()
  }

  private void deleteOrCancelVoucher() {
    if (currentVoucher == null) {
      return
    }
    try {
      int choice = javax.swing.JOptionPane.showConfirmDialog(
          this,
          I18n.instance.getString('voucherPanel.confirm.cannotDelete')
              .replace('{0}', currentVoucher.voucherNumber ?: ''),
          I18n.instance.getString('voucherPanel.button.void'),
          javax.swing.JOptionPane.YES_NO_OPTION
      )
      if (choice == javax.swing.JOptionPane.YES_OPTION) {
        Voucher correction = voucherService.createCorrectionVoucher(currentVoucher.id, null)
        showInfo(I18n.instance.format('voucherPanel.message.correctionCreated',
            correction.voucherNumber ?: ''))
        reloadVoucherList()
      }
    } catch (Exception ex) {
      showError(ex.message ?: I18n.instance.getString('voucherPanel.error.voidFailed'))
    }
  }

  private void removeSelectedLine() {
    int selectedRow = lineTable.selectedRow
    if (lineTable.editing) {
      lineTable.cellEditor.cancelCellEditing()
    }
    if (selectedRow >= 0 && selectedRow < lineTableModel.rowCount - 1) {
      lineTableModel.removeRow(selectedRow)
      refreshTotals()
    }
  }

  private void applyReadOnlyState() {
    boolean fiscalYearClosed = false
    if (currentVoucher != null) {
      readOnly = true
      if (currentVoucher.accountingDate != null) {
        try {
          fiscalYearClosed = accountingPeriodService.isDateLocked(
              activeCompanyManager.companyId, currentVoucher.accountingDate)
        } catch (Exception ex) {
          log.warning("Kunde inte avgöra om räkenskapsåret är låst för korrigeringsknappen: ${ex.message}")
          fiscalYearClosed = true
        }
      } else {
        fiscalYearClosed = true
      }
    } else if (activeCompanyManager.fiscalYear != null) {
      try {
        readOnly = accountingPeriodService.isDateLocked(
            activeCompanyManager.companyId, defaultDate())
      } catch (Exception ex) {
        log.warning("Kunde inte avgöra om räkenskapsåret är låst – skrivskyddar verifikatet: ${ex.message}")
        readOnly = true
      }
    } else {
      readOnly = false
    }
    lineTableModel.editable = !readOnly
    datePicker.enabled = !readOnly
    descriptionField.enabled = !readOnly
    seriesField.enabled = currentVoucher == null
    saveButton.enabled = !readOnly
    printButton.enabled = currentVoucher != null
    duplicateButton.enabled = currentVoucher != null
    voidButton.enabled = false
    correctionButton.enabled = currentVoucher != null
        && currentVoucher.accountingDate != null
        && currentVoucher.status == VoucherStatus.ACTIVE
        && !fiscalYearClosed
    addAttachmentButton.enabled = currentVoucher != null
  }

  private void refreshTotals() {
    BigDecimal debit = BigDecimal.ZERO
    BigDecimal credit = BigDecimal.ZERO
    try {
      lineTableModel.rows.each { LineEntry line ->
        debit = debit.add(parseAmount(line.debit))
        credit = credit.add(parseAmount(line.credit))
      }
      Locale loc = activeCompanyManager.companyLocale
      totalsLabel.text = I18n.instance.format('voucherPanel.totals',
          AmountFormatter.format(debit, loc),
          AmountFormatter.format(credit, loc),
          AmountFormatter.format(debit.subtract(credit), loc))
    } catch (IllegalArgumentException ignored) {
      totalsLabel.text = I18n.instance.getString('voucherPanel.totals.invalid')
    }
  }

  private void refreshAttachmentAndHistory() {
    if (currentVoucher == null) {
      clearAttachmentAndHistory()
      updateAttachmentButtons()
      return
    }
    if (tabs.selectedIndex == 1) {
      attachmentTableModel.setRows(attachmentService.listAttachments(currentVoucher.id))
    } else if (tabs.selectedIndex == 2) {
      auditLogTableModel.setRows(auditLogService.listEntriesForVoucher(currentVoucher.id))
    }
    updateAttachmentButtons()
  }

  private void clearAttachmentAndHistory() {
    attachmentTableModel.setRows([])
    auditLogTableModel.setRows([])
  }

  private void addAttachmentRequested() {
    if (currentVoucher == null) {
      showError(I18n.instance.getString('voucherPanel.error.saveBeforeAttachment'))
      return
    }
    SystemFileChooser chooser = new SystemFileChooser()
    int result = chooser.showOpenDialog(this)
    if (result != SystemFileChooser.APPROVE_OPTION || chooser.selectedFile == null) {
      return
    }
    try {
      attachmentService.addAttachment(currentVoucher.id, chooser.selectedFile.toPath())
      refreshAttachmentAndHistory()
      showInfo(I18n.instance.format('voucherPanel.message.attachmentAdded', chooser.selectedFile.name))
    } catch (Exception ex) {
      showError(ex.message ?: I18n.instance.getString('voucherPanel.error.attachmentFailed'))
    }
  }

  private void openSelectedAttachment() {
    int selectedRow = attachmentTable.selectedRow
    if (selectedRow < 0) {
      showError(I18n.instance.getString('voucherPanel.error.selectAttachment'))
      return
    }
    AttachmentMetadata selected = attachmentTableModel.rowAt(selectedRow)
    if (!Desktop.isDesktopSupported()) {
      showError(I18n.instance.getString('voucherEditor.error.desktopNotSupported'))
      return
    }
    Desktop desktop = Desktop.getDesktop()
    if (!desktop.isSupported(Desktop.Action.OPEN)) {
      showError(I18n.instance.getString('voucherEditor.error.openNotSupported'))
      return
    }
    try {
      desktop.open(attachmentService.resolveStoredPath(selected).toFile())
    } catch (IOException ex) {
      showError(ex.message ?: I18n.instance.getString('voucherEditor.error.attachmentOpenFailed'))
    }
  }

  private void printCurrentVoucher() {
    if (currentVoucher == null) {
      showError(I18n.instance.getString('voucherPanel.error.noVoucherToPrint'))
      return
    }
    String voucherNumber = currentVoucher.voucherNumber ?: String.valueOf(currentVoucher.id)
    JEditorPane document = new JEditorPane(
        'text/html',
        VoucherPrintDocument.buildHtml(currentVoucher, activeCompanyManager.companyLocale)
    )
    document.editable = false
    try {
      boolean completed = document.print(
          new MessageFormat(I18n.instance.format('voucherPanel.print.header', voucherNumber)),
          null,
          true,
          null,
          null,
          true
      )
      if (completed) {
        showInfo(I18n.instance.format('voucherPanel.message.printed', voucherNumber))
      }
    } catch (PrinterException exception) {
      showError(I18n.instance.format('voucherPanel.error.printFailed', exception.message ?: exception.class.simpleName))
    }
  }

  private void updateAttachmentButtons() {
    addAttachmentButton.enabled = currentVoucher != null
    openAttachmentButton.enabled = attachmentTable.selectedRow >= 0
  }

  private void recalculateBalances(int rowIndex) {
    LineEntry entry = lineTableModel.rows[rowIndex]
    recalculateBalance(entry)
    lineTableModel.fireTableRowsUpdated(rowIndex, rowIndex)
  }

  private void recalculateBalance(LineEntry entry) {
    if (!hasText(entry.accountNumber)) {
      entry.balanceBefore = null
      return
    }
    FiscalYear fy = activeCompanyManager.fiscalYear
    String cacheKey = entry.accountNumber
    BigDecimal balance = balanceCache.get(cacheKey)
    if (balance == null) {
      if (fy == null) {
        balance = BigDecimal.ZERO
      } else {
        try {
          balance = accountService.calculateAccountBalance(
              activeCompanyManager.companyId, fy.id, entry.accountNumber, currentVoucher?.id)
        } catch (Exception ignored) {
          balance = BigDecimal.ZERO
        }
      }
      balanceCache.put(cacheKey, balance)
    }
    entry.balanceBefore = balance
  }

  private void recalculateAllBalances() {
    Set<String> displayedAccounts = lineTableModel.rows
        .collect { LineEntry entry -> entry.accountNumber }
        .findAll { String accountNumber -> hasText(accountNumber) } as Set<String>
    Set<String> missingAccounts = displayedAccounts.findAll { String accountNumber ->
      !balanceCache.containsKey(accountNumber)
    } as Set<String>
    FiscalYear fy = activeCompanyManager.fiscalYear
    if (fy != null) {
      try {
        if (!missingAccounts.isEmpty()) {
          Map<String, BigDecimal> balances = accountService.calculateAccountBalances(
              activeCompanyManager.companyId, fy.id, missingAccounts, currentVoucher?.id)
          balanceCache.putAll(balances)
        }
      } catch (Exception ignored) {
        // Individual balance lookups below retain the previous fallback behaviour.
      }
    }
    lineTableModel.rows.each { LineEntry entry -> recalculateBalance(entry) }
    if (currentVoucher != null) {
      voucherBalanceCache[currentVoucher.id] = new LinkedHashMap<>(balanceCache)
    }
  }

  private void ensureAutoRow() {
    List<LineEntry> rows = lineTableModel.rows
    if (rows.isEmpty() || isRowFilled(rows.last())) {
      lineTableModel.addBlankRow()
    }
  }

  private static boolean isRowFilled(LineEntry entry) {
    hasText(entry.accountNumber) || hasText(entry.debit) || hasText(entry.credit)
        || hasText(entry.description)
  }

  private void updateLabels() {
    datePicker.locale = I18n.instance.locale
    prevButton.toolTipText = I18n.instance.getString('voucherPanel.button.prev')
    nextButton.toolTipText = I18n.instance.getString('voucherPanel.button.next')
    saveButton.toolTipText = I18n.instance.getString('voucherPanel.button.save')
    printButton.toolTipText = I18n.instance.getString('voucherPanel.button.print')
    duplicateButton.toolTipText = I18n.instance.getString('voucherPanel.button.duplicate')
    correctionButton.toolTipText = I18n.instance.getString('voucherPanel.button.createCorrection')
    voidButton.toolTipText = I18n.instance.getString('voucherPanel.button.void')
    addAttachmentButton.text = I18n.instance.getString('voucherPanel.button.addAttachment')
    openAttachmentButton.text = I18n.instance.getString('voucherPanel.button.openAttachment')
    jumpField.toolTipText = I18n.instance.getString('voucherPanel.label.jump')
    tabs.setTitleAt(0, I18n.instance.getString('voucherPanel.tab.lines'))
    tabs.setTitleAt(1, I18n.instance.getString('voucherPanel.tab.attachments'))
    tabs.setTitleAt(2, I18n.instance.getString('voucherPanel.tab.history'))
    lineTable.tableHeader.repaint()
    attachmentTable.tableHeader.repaint()
    auditLogTable.tableHeader.repaint()
    refreshTotals()
  }

  private static DatePicker createDatePicker() {
    DatePicker picker = new DatePicker(null, null, null, I18n.instance.locale)
    picker.textFieldPosition = TextFieldPosition.RIGHT
    picker
  }

  private BigDecimal parseAmount(String value) {
    try {
      AmountFormatter.parseAmountOrZero(value, activeCompanyManager.companyLocale)
    } catch (IllegalArgumentException ignored) {
      throw new IllegalArgumentException(I18n.instance.getString('voucherPanel.error.invalidAmount'))
    }
  }

  private void showInfo(String message) {
    feedbackArea.foreground = new Color(22, 101, 52)
    feedbackArea.text = message
  }

  private void showError(String message) {
    feedbackArea.foreground = new Color(153, 27, 27)
    feedbackArea.text = message
  }

  private void cursorToSuggestedAmountColumn(int row, String normalBalanceSide) {
    VoucherPairing.Suggestion suggestion = VoucherPairing.suggest(lineTableModel.rows, row, normalBalanceSide)
    lineTableModel.setSuggestedAmount(row, suggestion.column, suggestion.amount)
    cursorMover.call(row, suggestion.column)
  }

  private static boolean hasText(String value) {
    value != null && !value.isBlank()
  }

  @PackageScope
  static final class LineEntry {

    String accountNumber = ''
    String accountName = ''
    String normalBalanceSide = ''
    String description = ''
    String debit = ''
    String credit = ''
    BigDecimal balanceBefore = null
  }

  @PackageScope
  final class LineTableModel extends AbstractTableModel {

    private final List<LineEntry> rows = []
    private boolean editable = true

    void setEditable(boolean value) {
      this.editable = value
    }

    List<LineEntry> getRows() {
      rows
    }

    void setRows(List<VoucherLine> voucherLines) {
      int previousRowCount = rows.size()
      rows.clear()
      voucherLines.each { VoucherLine line ->
        LineEntry entry = new LineEntry()
        entry.accountNumber = line.accountNumber ?: ''
        entry.accountName = line.accountName ?: ''
        entry.description = line.description ?: ''
        entry.debit = amountText(line.debitAmount)
        entry.credit = amountText(line.creditAmount)
        rows << entry
      }
      if (rows.isEmpty()) {
        2.times { rows << new LineEntry() }
      } else {
        rows << new LineEntry()
      }
      if (rows.size() == previousRowCount) {
        fireTableRowsUpdated(0, rows.size() - 1)
      } else {
        fireTableDataChanged()
      }
    }

    void clear() {
      rows.clear()
      fireTableDataChanged()
    }

    void addBlankRow() {
      rows << new LineEntry()
      fireTableRowsInserted(rows.size() - 1, rows.size() - 1)
    }

    void addBlankRows(int count) {
      int start = rows.size()
      count.times {
        rows << new LineEntry()
      }
      fireTableRowsInserted(start, rows.size() - 1)
    }

    void removeRow(int rowIndex) {
      rows.remove(rowIndex)
      fireTableRowsDeleted(rowIndex, rowIndex)
    }

    void setAccountFromLookup(int rowIndex, String accountNumber, String accountName, String normalBalanceSide) {
      if (rowIndex < 0 || rowIndex >= rows.size()) {
        return
      }
      LineEntry row = rows[rowIndex]
      row.accountNumber = accountNumber ?: ''
      row.accountName = accountName ?: ''
      row.normalBalanceSide = normalBalanceSide ?: ''
      fireTableRowsUpdated(rowIndex, rowIndex)
      checkAutoRow()
    }

    void setSuggestedAmount(int rowIndex, int columnIndex, String amount) {
      if (rowIndex < 0 || rowIndex >= rows.size() || !hasText(amount)) {
        return
      }
      LineEntry row = rows[rowIndex]
      if (hasText(row.debit) || hasText(row.credit)) {
        return
      }
      if (columnIndex == 2) {
        row.debit = amount
      } else if (columnIndex == 3) {
        row.credit = amount
      } else {
        return
      }
      fireTableRowsUpdated(rowIndex, rowIndex)
      checkAutoRow()
    }

    List<VoucherLine> toVoucherLines() {
      rows.findAll { LineEntry row ->
        hasText(row.accountNumber) || hasText(row.description) || hasText(row.debit) || hasText(row.credit)
      }.collect { LineEntry row ->
        new VoucherLine(
            null,
            null,
            0,
            null,
            row.accountNumber,
            row.accountName,
            row.description,
            parseAmount(row.debit),
            parseAmount(row.credit)
        )
      }
    }

    @Override
    int getRowCount() {
      rows.size()
    }

    final int columnCount = 7

    @Override
    String getColumnName(int column) {
      switch (column) {
        case 0: return I18n.instance.getString('voucherPanel.table.account')
        case 1: return I18n.instance.getString('voucherPanel.table.accountDescription')
        case 2: return I18n.instance.getString('voucherPanel.table.debit')
        case 3: return I18n.instance.getString('voucherPanel.table.credit')
        case 4: return I18n.instance.getString('voucherPanel.table.text')
        case 5: return I18n.instance.getString('voucherPanel.table.balanceBefore')
        case 6: return I18n.instance.getString('voucherPanel.table.balanceAfter')
        default: return ''
      }
    }

    @Override
    Object getValueAt(int rowIndex, int columnIndex) {
      LineEntry row = rows[rowIndex]
      switch (columnIndex) {
        case 0:
          return row.accountNumber
        case 1:
          return row.accountName
        case 2:
          return row.debit
        case 3:
          return row.credit
        case 4:
          return row.description
        case 5:
          return AmountFormatter.formatOrEmpty(row.balanceBefore, activeCompanyManager.companyLocale)
        case 6:
          return calculateBalanceAfter(row)
        default:
          return ''
      }
    }

    private String calculateBalanceAfter(LineEntry row) {
      if (row.balanceBefore == null || !hasText(row.accountNumber)) {
        return ''
      }
      try {
        BigDecimal debit = parseAmount(row.debit)
        BigDecimal credit = parseAmount(row.credit)
        BigDecimal after = AccountService.calculateBalanceAfter(
            row.balanceBefore, debit, credit, row.normalBalanceSide)
        AmountFormatter.format(after, activeCompanyManager.companyLocale)
      } catch (IllegalArgumentException ignored) {
        ''
      }
    }

    @Override
    boolean isCellEditable(int rowIndex, int columnIndex) {
      if (!editable) {
        return false
      }
      columnIndex in [0, 1, 2, 3, 4]
    }

    @Override
    void setValueAt(Object value, int rowIndex, int columnIndex) {
      if (rowIndex < 0 || rowIndex >= rows.size()) {
        log.warning("setValueAt anropades med ogiltigt radindex ${rowIndex} (antal rader: ${rows.size()})")
        return
      }
      LineEntry row = rows[rowIndex]
      String text = value?.toString() ?: ''
      switch (columnIndex) {
        case 0:
          row.accountNumber = text.trim()
          Account lookedUp = lookupAccount(row.accountNumber)
          row.accountName = lookedUp?.accountName ?: ''
          row.normalBalanceSide = lookedUp?.normalBalanceSide ?: ''
          recalculateBalances(rowIndex)
          break
        case 1:
          row.accountName = text
          break
        case 2:
          row.debit = formatEditedAmount(text)
          if (hasText(row.debit)) {
            row.credit = ''
          }
          recalculateBalances(rowIndex)
          break
        case 3:
          row.credit = formatEditedAmount(text)
          if (hasText(row.credit)) {
            row.debit = ''
          }
          recalculateBalances(rowIndex)
          break
        case 4:
          row.description = text
          break
        default:
          break
      }
      fireTableRowsUpdated(rowIndex, rowIndex)
      checkAutoRow()
    }

    private void checkAutoRow() {
      if (!rows.isEmpty() && isRowFilled(rows.last())) {
        addBlankRow()
      }
    }

    private Account lookupAccount(String accountNumber) {
      String normalized = accountNumber?.trim()
      if (!normalized) {
        return null
      }
      try {
        accountService.findAccount(activeCompanyManager.companyId, normalized)
      } catch (Exception ex) {
        log.warning("Kontouppslagning misslyckades för ${normalized}: ${ex.message}")
        null
      }
    }

    private String amountText(BigDecimal amount) {
      AmountFormatter.formatOrEmpty(amount, activeCompanyManager.companyLocale)
    }

    private String formatEditedAmount(String text) {
      AmountFormatter.formatEdited(text, activeCompanyManager.companyLocale)
    }
  }

}
