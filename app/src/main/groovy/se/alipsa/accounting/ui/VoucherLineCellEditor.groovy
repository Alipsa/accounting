package se.alipsa.accounting.ui

import java.awt.Component
import java.awt.event.MouseEvent

import javax.swing.DefaultCellEditor
import javax.swing.JTable
import javax.swing.JTextField

/**
 * Cell editor that selects typed voucher-line values when keyboard editing starts.
 */
class VoucherLineCellEditor extends DefaultCellEditor {

  private boolean selectTextOnEdit = true

  VoucherLineCellEditor(JTextField textField) {
    super(textField)
  }

  @Override
  boolean isCellEditable(EventObject event) {
    selectTextOnEdit = !(event instanceof MouseEvent)
    super.isCellEditable(event)
  }

  @Override
  Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
    Component comp = super.getTableCellEditorComponent(table, value, isSelected, row, column)
    if (selectTextOnEdit && comp instanceof JTextField) {
      ((JTextField) comp).selectAll()
    }
    comp
  }
}
