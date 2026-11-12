package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.assertEquals

import com.formdev.flatlaf.util.UIScale
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

      assertEquals(datePicker.preferredSize.width as int, datePicker.width)
      assertEquals(datePicker.textField.preferredSize.width as int, datePicker.textField.width)
    }
  }

  @Test
  void descriptionFieldDeclaresAMinimumWidthSoItShrinksBeforeTheDatePicker() {
    JTextField descriptionField = findComponent(panel, JTextField) { JTextField field ->
      field.columns == 30
    }

    assertEquals(UIScale.scale(VoucherPanel.DESCRIPTION_FIELD_MIN_WIDTH), onEdt { descriptionField.minimumSize.width })
  }
}
