package se.alipsa.accounting.ui

import se.alipsa.accounting.domain.Account
import se.alipsa.accounting.domain.AttachmentMetadata
import se.alipsa.accounting.domain.AuditLogEntry
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.Voucher
import se.alipsa.accounting.domain.VoucherLine
import se.alipsa.accounting.domain.VoucherSeries
import se.alipsa.accounting.domain.VoucherStatus
import se.alipsa.accounting.service.AccountService
import se.alipsa.accounting.service.AccountingPeriodService
import se.alipsa.accounting.service.AttachmentService
import se.alipsa.accounting.service.AuditLogService
import se.alipsa.accounting.service.VoucherService
import se.alipsa.accounting.support.I18n
import se.alipsa.datepicker.DatePicker

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Desktop
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import java.time.LocalDate
import java.util.function.Consumer
import java.util.logging.Logger

import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.DefaultCellEditor
import javax.swing.JButton
import javax.swing.JFileChooser
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
final class VoucherPanel extends JPanel implements PropertyChangeListener {

  private static final Logger log = Logger.getLogger(VoucherPanel.name)

  private final VoucherService voucherService
  private final AccountService accountService
  private final AccountingPeriodService accountingPeriodService
  private final AttachmentService attachmentService
  private final AuditLogService auditLogService
  private final ActiveCompanyManager activeCompanyManager

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
  private JButton correctionButton
  private JButton voidButton
  private JButton addAttachmentButton
  private JButton openAttachmentButton
  private JTabbedPane tabs

  private LineTableModel lineTableModel
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

    jumpField.addActionListener { jumpToVoucher(jumpField.text) }
    jumpField.toolTipText = I18n.instance.getString('voucherPanel.label.jump')
    panel.add(jumpField)

    nextButton = new JButton('\u25B6')
    nextButton.toolTipText = I18n.instance.getString('voucherPanel.button.next')
    nextButton.addActionListener { navigateNext() }
    panel.add(nextButton)

    saveButton = new JButton('\uD83D\uDCBE')
    saveButton.toolTipText = I18n.instance.getString('voucherPanel.button.save')
    saveButton.addActionListener { saveVoucher() }
    panel.add(saveButton)

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
          tabFromCell(row, col)
        } else {
          int row = lineTable.selectedRow
          int col = lineTable.selectedColumn
          if (row >= 0 && col >= 0) {
            tabFromCell(row, col)
          }
        }
      }
    })
  }

  private static final List<Integer> EDITABLE_COLUMNS = [0, 1, 2, 3, 4]

  private void tabFromCell(int row, int col) {
    int currentIndex = EDITABLE_COLUMNS.indexOf(col)
    if (currentIndex >= 0 && currentIndex < EDITABLE_COLUMNS.size() - 1) {
      moveCursorToCell(row, EDITABLE_COLUMNS[currentIndex + 1])
    } else {
      ensureAutoRow()
      moveCursorToCell(row + 1, EDITABLE_COLUMNS[0])
    }
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

  private void advanceFromCell(int row, int col) {
    switch (col) {
      case 0: // Account number — jump to debet/kredit based on normalBalanceSide
        LineEntry entry = lineTableModel.rows[row]
        if (hasText(entry.normalBalanceSide)) {
          moveCursorToAmountColumn(row, entry.normalBalanceSide)
        } else {
          moveCursorToCell(row, 2)
        }
        break
      case 1: // Account description — same as account number
        LineEntry descEntry = lineTableModel.rows[row]
        if (hasText(descEntry.normalBalanceSide)) {
          moveCursorToAmountColumn(row, descEntry.normalBalanceSide)
        } else {
          moveCursorToCell(row, 2)
        }
        break
      case 2: // Debet — always advance to kredit
        moveCursorToCell(row, 3)
        break
      case 3: // Kredit — advance to next row account number
        ensureAutoRow()
        moveCursorToCell(row + 1, 0)
        break
      case 4: // Text — next row account
        ensureAutoRow()
        moveCursorToCell(row + 1, 0)
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
        moveCursorToAmountColumn(row, selected.normalBalanceSide)
      }
    } as Consumer<Account>
    AccountLookupPopup numberPopup = new AccountLookupPopup(
        accountService, activeCompanyManager.companyId, onNumberSelected)
    DefaultCellEditor numberEditor = new DefaultCellEditor(numberEditorField) {
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
        moveCursorToAmountColumn(row, selected.normalBalanceSide)
      }
    } as Consumer<Account>
    AccountLookupPopup namePopup = new AccountLookupPopup(
        accountService, activeCompanyManager.companyId, onNameSelected)
    DefaultCellEditor nameEditor = new DefaultCellEditor(nameEditorField) {
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
    [2, 3, 4].each { int col ->
      JTextField field = new JTextField()
      suppressEditorKeys(field)
      lineTable.columnModel.getColumn(col).cellEditor = new DefaultCellEditor(field)
    }
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
  }

  private void showVoucher(Voucher v) {
    currentVoucher = v
    balanceCache.clear()
    if (v == null) {
      showBlankVoucher()
      return
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
    refreshAttachmentAndHistory()
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
    attachmentTableModel.setRows([])
    auditLogTableModel.setRows([])
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
    LocalDate today = LocalDate.now()
    today.isBefore(fy.startDate) || today.isAfter(fy.endDate) ? fy.startDate : today
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
    int found = voucherList.findIndexOf { Voucher v ->
      v.voucherNumber == normalized
    }
    if (found >= 0) {
      currentIndex = found
      showVoucher(voucherList[currentIndex])
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
      Voucher saved
      if (currentVoucher != null) {
        saved = voucherService.updateVoucher(currentVoucher.id, date, description, lines)
      } else {
        String series = seriesField.text?.trim() ?: 'A'
        saved = voucherService.createVoucher(fy.id, series, date, description, lines)
      }
      showInfo(I18n.instance.getString('voucherPanel.message.saved').replace('{0}', saved.voucherNumber ?: ''))
      reloadVoucherList()
    } catch (Exception ex) {
      showError(ex.message ?: I18n.instance.getString('voucherPanel.error.saveFailed'))
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

  private void deleteOrCancelVoucher() {
    if (currentVoucher == null) {
      return
    }
    try {
      if (voucherService.isLastInSeries(currentVoucher.id)) {
        int choice = javax.swing.JOptionPane.showConfirmDialog(
            this,
            I18n.instance.getString('voucherPanel.confirm.delete')
                .replace('{0}', currentVoucher.voucherNumber ?: ''),
            I18n.instance.getString('voucherPanel.button.void'),
            javax.swing.JOptionPane.OK_CANCEL_OPTION
        )
        if (choice != javax.swing.JOptionPane.OK_OPTION) {
          return
        }
        voucherService.deleteVoucher(currentVoucher.id)
        showInfo(I18n.instance.getString('voucherPanel.message.deleted')
            .replace('{0}', currentVoucher.voucherNumber ?: ''))
        reloadVoucherList()
      } else {
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
    if (currentVoucher != null && currentVoucher.accountingDate != null) {
      try {
        readOnly = accountingPeriodService.isDateLocked(
            activeCompanyManager.companyId, currentVoucher.accountingDate)
      } catch (Exception ex) {
        log.warning("Kunde inte avgöra om perioden är låst – skrivskyddar verifikatet: ${ex.message}")
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
    voidButton.enabled = !readOnly && currentVoucher != null && currentVoucher.status == VoucherStatus.ACTIVE
    correctionButton.enabled = readOnly && currentVoucher != null && currentVoucher.status == VoucherStatus.ACTIVE
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
      totalsLabel.text = I18n.instance.format('voucherPanel.totals',
          debit.setScale(2).toPlainString(),
          credit.setScale(2).toPlainString(),
          debit.subtract(credit).setScale(2).toPlainString())
    } catch (IllegalArgumentException ignored) {
      totalsLabel.text = I18n.instance.getString('voucherPanel.totals.invalid')
    }
  }

  private void refreshAttachmentAndHistory() {
    if (currentVoucher == null) {
      attachmentTableModel.setRows([])
      auditLogTableModel.setRows([])
      updateAttachmentButtons()
      return
    }
    attachmentTableModel.setRows(attachmentService.listAttachments(currentVoucher.id))
    auditLogTableModel.setRows(auditLogService.listEntriesForVoucher(currentVoucher.id))
    updateAttachmentButtons()
  }

  private void addAttachmentRequested() {
    if (currentVoucher == null) {
      showError(I18n.instance.getString('voucherPanel.error.saveBeforeAttachment'))
      return
    }
    JFileChooser chooser = new JFileChooser()
    int result = chooser.showOpenDialog(this)
    if (result != JFileChooser.APPROVE_OPTION || chooser.selectedFile == null) {
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

  private void updateAttachmentButtons() {
    addAttachmentButton.enabled = currentVoucher != null
    openAttachmentButton.enabled = attachmentTable.selectedRow >= 0
  }

  private void recalculateBalances(int rowIndex) {
    LineEntry entry = lineTableModel.rows[rowIndex]
    if (!hasText(entry.accountNumber)) {
      entry.balanceBefore = null
      lineTableModel.fireTableRowsUpdated(rowIndex, rowIndex)
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
    lineTableModel.fireTableRowsUpdated(rowIndex, rowIndex)
  }

  private void recalculateAllBalances() {
    for (int i = 0; i < lineTableModel.rows.size(); i++) {
      recalculateBalances(i)
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
    picker.textField.editable = false
    picker
  }

  private static BigDecimal parseAmount(String value) {
    String normalized = value?.trim()
    if (!normalized) {
      return BigDecimal.ZERO.setScale(2)
    }
    try {
      new BigDecimal(normalized.replace(',', '.')).setScale(2)
    } catch (NumberFormatException exception) {
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

  private void moveCursorToAmountColumn(int row, String normalBalanceSide) {
    moveCursorToCell(row, 'CREDIT' == normalBalanceSide ? 3 : 2)
  }

  private static boolean hasText(String value) {
    value != null && !value.isBlank()
  }

  private static String shortHash(String hash) {
    hash == null || hash.length() <= 12 ? hash ?: '' : hash.substring(0, 12)
  }

  private static String formatFileSize(long bytes) {
    if (bytes < 1024) {
      return "${bytes} B"
    }
    if (bytes < 1024L * 1024L) {
      return String.format(Locale.ROOT, '%.1f kB', bytes / 1024.0d)
    }
    String.format(Locale.ROOT, '%.1f MB', bytes / (1024.0d * 1024.0d))
  }

  private static final class LineEntry {

    String accountNumber = ''
    String accountName = ''
    String normalBalanceSide = ''
    String description = ''
    String debit = ''
    String credit = ''
    BigDecimal balanceBefore = null
  }

  private final class LineTableModel extends AbstractTableModel {

    private final List<LineEntry> rows = []
    private boolean editable = true

    void setEditable(boolean value) {
      this.editable = value
    }

    List<LineEntry> getRows() {
      rows
    }

    void setRows(List<VoucherLine> voucherLines) {
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
        addBlankRows(2)
        return
      }
      fireTableDataChanged()
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
          return row.balanceBefore == null ? '' : row.balanceBefore.toPlainString()
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
        after.toPlainString()
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
          row.debit = text.trim()
          if (hasText(row.debit)) {
            row.credit = ''
          }
          recalculateBalances(rowIndex)
          break
        case 3:
          row.credit = text.trim()
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

    private static String amountText(BigDecimal amount) {
      amount == null || amount == BigDecimal.ZERO ? '' : amount.setScale(2).toPlainString()
    }
  }

  private static final class AttachmentTableModel extends AbstractTableModel {

    private List<AttachmentMetadata> rows = []

    void setRows(List<AttachmentMetadata> attachments) {
      rows = new ArrayList<>(attachments)
      fireTableDataChanged()
    }

    AttachmentMetadata rowAt(int rowIndex) {
      rows[rowIndex]
    }

    @Override
    int getRowCount() {
      rows.size()
    }

    final int columnCount = 5

    @Override
    String getColumnName(int column) {
      switch (column) {
        case 0: return I18n.instance.getString('voucherEditor.table.attachment.fileName')
        case 1: return I18n.instance.getString('voucherEditor.table.attachment.type')
        case 2: return I18n.instance.getString('voucherEditor.table.attachment.size')
        case 3: return I18n.instance.getString('voucherEditor.table.attachment.checksum')
        case 4: return I18n.instance.getString('voucherEditor.table.attachment.saved')
        default: return ''
      }
    }

    @Override
    Object getValueAt(int rowIndex, int columnIndex) {
      AttachmentMetadata attachment = rows[rowIndex]
      switch (columnIndex) {
        case 0:
          return attachment.originalFileName
        case 1:
          return attachment.contentType ?: ''
        case 2:
          return formatFileSize(attachment.fileSize)
        case 3:
          return shortHash(attachment.checksumSha256)
        case 4:
          return attachment.createdAt
        default:
          return ''
      }
    }
  }

  private static final class AuditLogTableModel extends AbstractTableModel {

    private List<AuditLogEntry> rows = []

    void setRows(List<AuditLogEntry> entries) {
      rows = new ArrayList<>(entries)
      fireTableDataChanged()
    }

    @Override
    int getRowCount() {
      rows.size()
    }

    final int columnCount = 5

    @Override
    String getColumnName(int column) {
      switch (column) {
        case 0: return I18n.instance.getString('voucherEditor.table.auditLog.time')
        case 1: return I18n.instance.getString('voucherEditor.table.auditLog.event')
        case 2: return I18n.instance.getString('voucherEditor.table.auditLog.summary')
        case 3: return I18n.instance.getString('voucherEditor.table.auditLog.actor')
        case 4: return I18n.instance.getString('voucherEditor.table.auditLog.hash')
        default: return ''
      }
    }

    @Override
    Object getValueAt(int rowIndex, int columnIndex) {
      AuditLogEntry entry = rows[rowIndex]
      switch (columnIndex) {
        case 0:
          return entry.createdAt
        case 1:
          return entry.eventType
        case 2:
          return entry.summary
        case 3:
          return entry.actor
        case 4:
          return shortHash(entry.entryHash)
        default:
          return ''
      }
    }
  }
}
