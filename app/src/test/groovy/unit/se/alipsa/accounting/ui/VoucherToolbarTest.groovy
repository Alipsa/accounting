package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.*

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLightLaf
import com.formdev.flatlaf.extras.FlatSVGIcon
import com.formdev.flatlaf.util.UIScale
import org.junit.jupiter.api.Test

import java.awt.Color
import java.awt.image.BufferedImage

import javax.swing.JButton
import javax.swing.LookAndFeel
import javax.swing.SwingUtilities
import javax.swing.UIManager

final class VoucherToolbarTest {

  @Test
  void svgCommandsHaveConsistentSizesAndFollowForegroundInBothThemes() {
    SwingUtilities.invokeAndWait {
      LookAndFeel original = UIManager.lookAndFeel
      try {
        [new FlatLightLaf(), new FlatDarkLaf()].each { LookAndFeel theme ->
          UIManager.setLookAndFeel(theme)
          ['first', 'prev', 'next', 'last', 'save', 'new-after-save', 'print',
           'duplicate', 'correction', 'void', 'calendar', 'new-series'].each { String name ->
            JButton button = VoucherToolbar.button(name, 'voucherPanel.button.save') { null }
            FlatSVGIcon icon = button.icon as FlatSVGIcon
            assertTrue(icon.hasFound(), "Missing voucher icon ${name}".toString())
            assertEquals(UIScale.scale(34), button.preferredSize.height as int)
            assertEquals(UIScale.scale(34), button.preferredSize.width as int)
            assertEquals(button.toolTipText, button.accessibleContext.accessibleName)
            button.foreground = Color.MAGENTA
            BufferedImage image = new BufferedImage(icon.iconWidth, icon.iconHeight, BufferedImage.TYPE_INT_ARGB)
            def graphics = image.createGraphics()
            try {
              icon.paintIcon(button, graphics, 0, 0)
            } finally {
              graphics.dispose()
            }
            assertTrue(hasForeground(image), "Voucher icon ${name} must use the component foreground".toString())
          }
        }
      } finally {
        UIManager.setLookAndFeel(original)
      }
    }
  }

  private static boolean hasForeground(BufferedImage image) {
    for (int y = 0; y < image.height; y++) {
      for (int x = 0; x < image.width; x++) {
        Color pixel = new Color(image.getRGB(x, y), true)
        if (pixel.alpha > 0 && pixel.red == 255 && pixel.green == 0 && pixel.blue == 255) {
          return true
        }
      }
    }
    false
  }
}
