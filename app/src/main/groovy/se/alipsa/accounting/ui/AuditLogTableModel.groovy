package se.alipsa.accounting.ui

import se.alipsa.accounting.domain.AuditLogEntry
import se.alipsa.accounting.support.I18n

import javax.swing.table.AbstractTableModel

/**
 * Table model for voucher audit log entries.
 */
final class AuditLogTableModel extends AbstractTableModel {

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

  private static String shortHash(String hash) {
    hash == null || hash.length() <= 12 ? hash ?: '' : hash.substring(0, 12)
  }
}
