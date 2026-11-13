package se.alipsa.accounting.ui

import com.formdev.flatlaf.util.UIScale

import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Insets

import javax.swing.JPanel

/** Reserves the height of wrapped command rows when placed in BorderLayout.NORTH. */
final class VoucherToolbarPanel extends JPanel {

  VoucherToolbarPanel() {
    super(new FlowLayout(FlowLayout.LEFT, UIScale.scale(6), UIScale.scale(2)))
  }

  @Override
  Dimension getPreferredSize() {
    Dimension natural = super.getPreferredSize()
    if (parent == null || parent.width <= 0) {
      return natural
    }
    FlowLayout flow = layout as FlowLayout
    Insets padding = insets
    int available = parent.width - parent.insets.left - parent.insets.right
        - padding.left - padding.right - 2 * flow.hgap
    int height = padding.top + padding.bottom + 2 * flow.vgap
    int rowWidth = 0
    int rowHeight = 0
    for (Component component : components) {
      if (!component.visible) {
        continue
      }
      Dimension size = component.preferredSize
      if (rowWidth > 0 && rowWidth + flow.hgap + size.width > available) {
        height += rowHeight + flow.vgap
        rowWidth = 0
        rowHeight = 0
      }
      rowWidth += (rowWidth > 0 ? flow.hgap : 0) + (size.width as int)
      rowHeight = Math.max(rowHeight, size.height as int)
    }
    new Dimension(natural.width as int, height + rowHeight)
  }
}
