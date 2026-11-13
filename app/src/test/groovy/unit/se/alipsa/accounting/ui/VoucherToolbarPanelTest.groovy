package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.*

import org.junit.jupiter.api.Test

import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout

import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities

final class VoucherToolbarPanelTest {

  @Test
  void wrappedHeightMatchesFlowLayoutWithPanelAndParentInsetsAtEveryWidth() {
    SwingUtilities.invokeAndWait {
      JPanel parent = new JPanel(new BorderLayout())
      parent.border = BorderFactory.createEmptyBorder(3, 7, 11, 13)
      VoucherToolbarPanel toolbar = new VoucherToolbarPanel()
      toolbar.border = BorderFactory.createEmptyBorder(4, 9, 6, 5)
      [51, 88, 99, 34, 107, 68, 42].each { int width ->
        JButton button = new JButton()
        button.preferredSize = new Dimension(width, width == 88 ? 42 : 34)
        toolbar.add(button)
      }
      parent.add(toolbar, BorderLayout.NORTH)
      (200..1300).each { int width ->
        parent.setSize(width, 600)
        parent.doLayout()
        toolbar.doLayout()
        toolbar.components.each { Component child ->
          assertTrue(child.x >= 0 && child.x + child.width <= toolbar.width)
          assertTrue(child.y >= 0 && child.y + child.height <= toolbar.height,
              "Wrapped row must fit at width ${width}".toString())
        }
        int bottom = toolbar.components.collect { Component child -> child.y + child.height }.max() as int
        assertEquals(bottom + toolbar.insets.bottom + (toolbar.layout as FlowLayout).vgap, toolbar.height,
            "No extra blank row at width ${width}".toString())
      }
    }
  }
}
