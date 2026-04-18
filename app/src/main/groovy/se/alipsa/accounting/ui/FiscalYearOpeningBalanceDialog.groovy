package se.alipsa.accounting.ui

import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.service.OpeningBalanceService
import se.alipsa.accounting.service.OpeningBalanceService.OpeningBalanceDrift
import se.alipsa.accounting.service.OpeningBalanceService.OpeningBalanceLine
import se.alipsa.accounting.support.I18n

import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Frame

import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.table.AbstractTableModel

/**
 * Fiscal-year scoped editor for opening balances.
 */
final class FiscalYearOpeningBalanceDialog extends JDialog {

  private final OpeningBalanceService openingBalanceService
  private final long companyId
  private final FiscalYear fiscalYear
  private final Runnable onSave
  private final OpeningBalanceTableModel tableModel = new OpeningBalanceTableModel()
  private final JTable table = new JTable(tableModel)
  private final JTextArea infoArea = new JTextArea(4, 48)
  private final JButton refreshButton = new JButton()

  FiscalYearOpeningBalanceDialog(
      Frame owner,
      OpeningBalanceService openingBalanceService,
      long companyId,
      FiscalYear fiscalYear,
      Runnable onSave
  ) {
    super(owner, I18n.instance.getString('fiscalYearOpeningBalanceDialog.title'), true)
    this.openingBalanceService = openingBalanceService
    this.companyId = companyId
    this.fiscalYear = fiscalYear
    this.onSave = onSave ?: ({ } as Runnable)
    buildUi()
    reloadData()
  }

  static void showDialog(
      Frame owner,
      OpeningBalanceService openingBalanceService,
      long companyId,
      FiscalYear fiscalYear,
      Runnable onSave
  ) {
    FiscalYearOpeningBalanceDialog dialog = new FiscalYearOpeningBalanceDialog(
        owner,
        openingBalanceService,
        companyId,
        fiscalYear,
        onSave
    )
    dialog.visible = true
  }

  private void buildUi() {
    setLayout(new BorderLayout(12, 12))
    ((JPanel) contentPane).border = BorderFactory.createEmptyBorder(12, 12, 12, 12)

    add(new JLabel(I18n.instance.format(
        'fiscalYearOpeningBalanceDialog.header',
        fiscalYear.name,
        fiscalYear.startDate,
        fiscalYear.endDate
    )), BorderLayout.NORTH)

    table.putClientProperty('terminateEditOnFocusLost', Boolean.TRUE)
    add(new JScrollPane(table), BorderLayout.CENTER)

    infoArea.editable = false
    infoArea.lineWrap = true
    infoArea.wrapStyleWord = true
    infoArea.background = background

    JPanel footer = new JPanel(new BorderLayout(0, 8))
    footer.add(infoArea, BorderLayout.CENTER)
    footer.add(buildActions(), BorderLayout.SOUTH)
    add(footer, BorderLayout.SOUTH)

    pack()
    setMinimumSize(size)
    setLocationRelativeTo(owner)
  }

  private JPanel buildActions() {
    JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT))
    refreshButton.text = I18n.instance.getString('fiscalYearOpeningBalanceDialog.button.refresh')
    refreshButton.addActionListener { refreshFromPreviousYearRequested() }

    JButton saveButton = new JButton(I18n.instance.getString('fiscalYearOpeningBalanceDialog.button.save'))
    saveButton.addActionListener { saveRequested() }

    JButton closeButton = new JButton(I18n.instance.getString('fiscalYearOpeningBalanceDialog.button.close'))
    closeButton.addActionListener { dispose() }

    panel.add(refreshButton)
    panel.add(saveButton)
    panel.add(closeButton)
    panel
  }

  private void reloadData() {
    tableModel.setRows(openingBalanceService.listForFiscalYear(companyId, fiscalYear.id))
    refreshButton.enabled = resolveSourceFiscalYear() != null
    renderDriftSummary()
  }

  private void saveRequested() {
    if (table.editing) {
      table.cellEditor.stopCellEditing()
    }
    try {
      tableModel.rows.findAll { OpeningBalanceRow row -> row.dirty }.each { OpeningBalanceRow row ->
        openingBalanceService.saveManualOpeningBalance(fiscalYear.id, row.accountNumber, parseAmount(row.amountText))
      }
      onSave.run()
      reloadData()
      renderMessage(new Color(22, 101, 52), I18n.instance.getString('fiscalYearOpeningBalanceDialog.message.saved'))
    } catch (IllegalArgumentException exception) {
      renderMessage(new Color(153, 27, 27), exception.message)
    }
  }

  private void refreshFromPreviousYearRequested() {
    FiscalYear sourceFiscalYear = resolveSourceFiscalYear()
    if (sourceFiscalYear == null) {
      renderMessage(new Color(153, 27, 27), I18n.instance.getString('fiscalYearOpeningBalanceDialog.message.noPreviousYear'))
      return
    }
    String messageKey = openingBalanceService.hasVoucherActivity(fiscalYear.id)
        ? 'fiscalYearOpeningBalanceDialog.confirm.refreshWithVouchers'
        : 'fiscalYearOpeningBalanceDialog.confirm.refresh'
    int choice = JOptionPane.showConfirmDialog(
        this,
        I18n.instance.format(messageKey, sourceFiscalYear.name, fiscalYear.name),
        I18n.instance.getString('fiscalYearOpeningBalanceDialog.confirm.title'),
        JOptionPane.OK_CANCEL_OPTION
    )
    if (choice != JOptionPane.OK_OPTION) {
      return
    }
    int updated = openingBalanceService.transferFromPreviousFiscalYear(sourceFiscalYear.id, fiscalYear.id)
    onSave.run()
    reloadData()
    renderMessage(
        new Color(22, 101, 52),
        I18n.instance.format('fiscalYearOpeningBalanceDialog.message.refreshed', updated as Object, sourceFiscalYear.name)
    )
  }

  private FiscalYear resolveSourceFiscalYear() {
    FiscalYear source = openingBalanceService.findAutoManagedSourceFiscalYear(fiscalYear.id)
    source ?: openingBalanceService.findImmediatePreviousFiscalYear(companyId, fiscalYear.id)
  }

  private void renderDriftSummary() {
    List<OpeningBalanceDrift> drift = openingBalanceService.detectDrift(fiscalYear.id)
    if (drift.isEmpty()) {
      renderMessage(new Color(22, 101, 52), I18n.instance.getString('fiscalYearOpeningBalanceDialog.message.noDrift'))
      return
    }
    renderMessage(
        new Color(146, 64, 14),
        I18n.instance.format('fiscalYearOpeningBalanceDialog.message.driftDetected', drift.size() as Object)
    )
  }

  private void renderMessage(Color color, String message) {
    infoArea.foreground = color
    infoArea.text = message ?: ''
  }

  private static BigDecimal parseAmount(String value) {
    String normalized = value?.trim()
    if (!normalized) {
      return BigDecimal.ZERO
    }
    try {
      new BigDecimal(normalized.replace(',', '.'))
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(I18n.instance.getString('fiscalYearOpeningBalanceDialog.error.invalidAmount'))
    }
  }

  private static String originLabel(String originType) {
    String key = "fiscalYearOpeningBalanceDialog.origin.${originType ?: OpeningBalanceService.ORIGIN_MANUAL}"
    I18n.instance.getString(key)
  }

  private final class OpeningBalanceTableModel extends AbstractTableModel {

    final int columnCount = 4
    private List<OpeningBalanceRow> rows = []

    void setRows(List<OpeningBalanceLine> lines) {
      rows = (lines ?: []).collect { OpeningBalanceLine line ->
        new OpeningBalanceRow(
            line.accountNumber,
            line.accountName,
            line.amount == BigDecimal.ZERO ? '' : line.amount.toPlainString(),
            originLabel(line.originType)
        )
      }
      fireTableDataChanged()
    }

    @Override
    int getRowCount() {
      rows.size()
    }

    @Override
    String getColumnName(int column) {
      switch (column) {
        case 0: return I18n.instance.getString('fiscalYearOpeningBalanceDialog.table.account')
        case 1: return I18n.instance.getString('fiscalYearOpeningBalanceDialog.table.name')
        case 2: return I18n.instance.getString('fiscalYearOpeningBalanceDialog.table.amount')
        case 3: return I18n.instance.getString('fiscalYearOpeningBalanceDialog.table.origin')
        default: return ''
      }
    }

    @Override
    Object getValueAt(int rowIndex, int columnIndex) {
      OpeningBalanceRow row = rows[rowIndex]
      switch (columnIndex) {
        case 0: return row.accountNumber
        case 1: return row.accountName
        case 2: return row.amountText
        case 3: return row.originLabel
        default: return ''
      }
    }

    @Override
    boolean isCellEditable(int rowIndex, int columnIndex) {
      columnIndex == 2
    }

    @Override
    void setValueAt(Object value, int rowIndex, int columnIndex) {
      if (columnIndex != 2) {
        return
      }
      OpeningBalanceRow row = rows[rowIndex]
      String text = value == null ? '' : value.toString().trim()
      if (row.amountText != text) {
        row.amountText = text
        row.originLabel = originLabel(OpeningBalanceService.ORIGIN_MANUAL)
        row.dirty = true
        fireTableRowsUpdated(rowIndex, rowIndex)
      }
    }
  }

  private static final class OpeningBalanceRow {
    final String accountNumber
    final String accountName
    String amountText
    String originLabel
    boolean dirty

    OpeningBalanceRow(String accountNumber, String accountName, String amountText, String originLabel) {
      this.accountNumber = accountNumber
      this.accountName = accountName
      this.amountText = amountText
      this.originLabel = originLabel
    }
  }
}
