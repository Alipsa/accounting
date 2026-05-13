package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.assertEquals

import org.junit.jupiter.api.Test

import java.awt.event.KeyEvent
import java.awt.event.MouseEvent

import javax.swing.JTable
import javax.swing.JTextField

final class VoucherLineCellEditorTest {

  @Test
  void selectsExistingTextWhenEditingStartsFromKeyboard() {
    JTextField field = new JTextField()
    VoucherLineCellEditor editor = new VoucherLineCellEditor(field)
    JTable table = new JTable(1, 1)

    editor.isCellEditable(new KeyEvent(table, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0,
        KeyEvent.VK_UNDEFINED, '5' as char))
    editor.getTableCellEditorComponent(table, '1234', true, 0, 0)

    assertEquals(0, field.selectionStart)
    assertEquals(4, field.selectionEnd)
  }

  @Test
  void keepsCaretSelectionWhenEditingStartsFromMouse() {
    JTextField field = new JTextField()
    VoucherLineCellEditor editor = new VoucherLineCellEditor(field)
    JTable table = new JTable(1, 1)

    editor.isCellEditable(new MouseEvent(table, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
        4, 4, 2, false))
    editor.getTableCellEditorComponent(table, '1234', true, 0, 0)

    assertEquals(field.selectionStart, field.selectionEnd)
  }
}
