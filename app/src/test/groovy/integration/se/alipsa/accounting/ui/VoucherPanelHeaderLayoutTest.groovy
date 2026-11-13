package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.*

import com.formdev.flatlaf.extras.FlatSVGIcon
import org.junit.jupiter.api.Test

import se.alipsa.accounting.support.I18n
import se.alipsa.datepicker.DatePicker

import java.awt.Component
import java.awt.Container

import javax.swing.AbstractButton
import javax.swing.JButton
import javax.swing.JTabbedPane
import javax.swing.JToggleButton

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
  void realCommandsAndCalendarHaveSvgIconsAndTranslatedActionNames() {
    withLocales {
      onEdt {
        Map<String, String> actions = [first: 'first', prev: 'prev', next: 'next', last: 'last',
            save: 'save', print: 'print', duplicate: 'duplicate', correction: 'createCorrection', 'new-series': 'newSeries']
        actions.each { String iconName, String action ->
          String label = I18n.instance.getString("voucherPanel.button.${action}")
          JButton button = findComponent(panel, JButton) { JButton candidate -> candidate.toolTipText == label }
          assertEquals(label, button.accessibleContext.accessibleName)
          assertSvg(button, iconName)
        }
        DatePicker picker = findComponent(panel, DatePicker) { true }
        String chooseDate = I18n.instance.getString('voucherPanel.button.chooseDate')
        assertEquals(chooseDate, picker.calendarButton.toolTipText)
        assertEquals(chooseDate, picker.calendarButton.accessibleContext.accessibleName)
        assertNotEquals(I18n.instance.getString('voucherPanel.label.date'), chooseDate)
        assertSvg(picker.calendarButton, 'calendar')
        JToggleButton toggle = findComponent(panel, JToggleButton) { true }
        assertSvg(toggle, 'new-after-save')
        assertEquals(I18n.instance.getString('voucherPanel.toggle.advanceAfterSave'), toggle.toolTipText)
        assertEquals(I18n.instance.getString('voucherPanel.toggle.advanceAfterSave.on'), toggle.text)
        assertTrue(toggle.selected)
        VoucherToolbarPanel toolbar = findComponent(panel, VoucherToolbarPanel) { true }
        assertFalse(toolbar.components.any { Component child ->
          child instanceof AbstractButton && child.toolTipText == I18n.instance.getString('voucherPanel.button.void')
        })
      }
    }
  }

  @Test
  void wrappedCommandsRemainVisibleAtNarrowWidthsInBothLanguages() {
    withLocales {
      onEdt {
        VoucherToolbarPanel toolbar = findComponent(panel, VoucherToolbarPanel) { true }
        JTabbedPane tabs = findComponent(panel, JTabbedPane) { true }
        [1200, 900, 800, 700, 600, 400, 1200].each { int width ->
          panel.setSize(width, 600)
          layoutTree(panel)
          toolbar.components.findAll { Component child -> child.visible }.each { Component child ->
            assertTrue(child.x >= 0 && child.x + child.width <= toolbar.width,
                "Command must fit horizontally at width ${width}".toString())
            assertTrue(child.y >= 0 && child.y + child.height <= toolbar.height,
                "Wrapped command must fit vertically at width ${width}".toString())
          }
          assertTrue(tabs.y >= toolbar.y + toolbar.height)
        }
      }
    }
  }

  private void withLocales(Closure<?> test) {
    Locale original = I18n.instance.locale
    try {
      [Locale.ENGLISH, Locale.forLanguageTag('sv')].each { Locale locale ->
        onEdt { I18n.instance.locale = locale }
        onEdt { null } // Drain the queued label refresh before inspecting the controls.
        test.call()
      }
    } finally {
      onEdt { I18n.instance.locale = original }
      onEdt { null }
    }
  }

  private static void assertSvg(AbstractButton button, String iconName) {
    assertInstanceOf(FlatSVGIcon, button.icon)
    FlatSVGIcon icon = button.icon as FlatSVGIcon
    assertTrue(icon.hasFound())
    assertEquals("icons/voucher/${iconName}.svg".toString(), icon.name)
  }

  private static void layoutTree(Container container) {
    container.doLayout()
    container.components.each { Component child ->
      if (child instanceof Container) {
        layoutTree(child)
      }
    }
  }
}
