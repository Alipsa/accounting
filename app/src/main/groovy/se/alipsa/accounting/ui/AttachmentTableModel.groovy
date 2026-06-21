package se.alipsa.accounting.ui

import se.alipsa.accounting.domain.AttachmentMetadata
import se.alipsa.accounting.support.I18n

import javax.swing.table.AbstractTableModel

/**
 * Table model for voucher attachment metadata.
 */
final class AttachmentTableModel extends AbstractTableModel {

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
}
