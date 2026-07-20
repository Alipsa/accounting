package se.alipsa.accounting.ui

import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints

import javax.swing.Icon

/**
 * Cross-platform save icon that does not depend on an emoji font.
 */
final class VoucherSaveIcon implements Icon {

  final int iconWidth = 16
  final int iconHeight = 16

  @Override
  void paintIcon(Component component, Graphics graphics, int x, int y) {
    Graphics2D g2 = graphics.create() as Graphics2D
    try {
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      Color color = component.foreground ?: Color.DARK_GRAY
      g2.setColor(color)
      g2.drawRoundRect(x + 2, y + 1, 12, 14, 2, 2)
      g2.fillRect(x + 5, y + 2, 6, 4)
      g2.drawRect(x + 5, y + 9, 6, 4)
      g2.drawLine(x + 7, y + 2, x + 7, y + 5)
    } finally {
      g2.dispose()
    }
  }
}
