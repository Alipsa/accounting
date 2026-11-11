package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.Test

import se.alipsa.datepicker.DatePicker

import java.awt.Container

import javax.swing.JTextField

final class VoucherPanelHeaderLayoutTest extends VoucherPanelTestSupport {

  @Test
  void datePickerKeepsItsTextFieldVisibleWhenTheHeaderBarIsNarrowed() {
    DatePicker datePicker = findComponent(panel, DatePicker) { true }

    onEdt {
      Container headerBar = datePicker.parent
      int headerWidth = headerBar.preferredSize.width as int
      int headerHeight = headerBar.preferredSize.height as int
      int narrowedWidth = headerWidth - 100
      headerBar.setSize(narrowedWidth, headerHeight)
      headerBar.doLayout()
      datePicker.doLayout()

      assertEquals(datePicker.minimumSize.width, datePicker.width)
      assertTrue(datePicker.textField.width > 0)
    }
  }

  @Test
  void descriptionFieldDeclaresAMinimumWidthSoItShrinksBeforeTheDatePicker() {
    JTextField descriptionField = findComponent(panel, JTextField) { JTextField field ->
      field.columns == 30
    }

    assertEquals(120, onEdt { descriptionField.minimumSize.width })
  }
}
