package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.*

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLightLaf
import com.formdev.flatlaf.extras.FlatSVGIcon
import com.formdev.flatlaf.util.UIScale
import org.junit.jupiter.api.Test

import se.alipsa.accounting.support.I18n

import java.awt.Color
import java.awt.Graphics2D
import java.awt.image.BufferedImage

import javax.swing.AbstractButton
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JToggleButton
import javax.swing.LookAndFeel
import javax.swing.SwingUtilities
import javax.swing.UIDefaults
import javax.swing.UIManager

final class VoucherToolbarTest {

  @Test
  void commandIconsLoadAndFollowComponentForegroundInBothThemes() {
    withThemes {
      Map<String, String> actions = [first: 'first', prev: 'prev', next: 'next', last: 'last',
          print: 'print', duplicate: 'duplicate', correction: 'createCorrection', 'new-series': 'newSeries']
      actions.each { String iconName, String action ->
        String label = I18n.instance.getString("voucherPanel.button.${action}")
        JButton button = VoucherToolbar.button(iconName, "voucherPanel.button.${action}") { null }
        assertTrue((button.icon as FlatSVGIcon).hasFound())
        assertEquals(UIScale.scale(34), button.preferredSize.height as int)
        assertEquals(UIScale.scale(34), button.preferredSize.width as int)
        assertEquals(label, button.toolTipText)
        assertEquals(label, button.accessibleContext.accessibleName)
        button.foreground = Color.MAGENTA
        assertIconColor(button, Color.MAGENTA)
      }
    }
  }

  @Test
  void disabledSaveIconUsesDisabledTextWithoutAnAdditionalGrayFilter() {
    withThemes {
      JButton button = VoucherToolbar.saveButton { null }
      assertIconColor(button, button.foreground)
      button.enabled = false
      assertIconColor(button, UIManager.getColor('Button.disabledText'))
    }
  }

  @Test
  void advanceToggleShowsItsStateInTextAndUsesTheThemeToggleForeground() {
    withThemes {
      JToggleButton toggle = VoucherToolbar.advanceToggle()
      assertTrue(toggle.selected)
      assertEquals(I18n.instance.getString('voucherPanel.toggle.advanceAfterSave.on'), toggle.text)
      assertIconColor(toggle, UIManager.getColor('ToggleButton.selectedForeground') ?: toggle.foreground)
      toggle.enabled = false
      assertIconColor(toggle, UIManager.getColor('Button.disabledText'))
      toggle.enabled = true
      toggle.doClick()
      assertFalse(toggle.selected)
      assertEquals(I18n.instance.getString('voucherPanel.toggle.advanceAfterSave.off'), toggle.text)
      assertIconColor(toggle, toggle.foreground)
    }
  }

  @Test
  void labelledButtonsRecomputeTheirSizeWhenTheFontChanges() {
    withThemes {
      [VoucherToolbar.saveButton { null }, VoucherToolbar.advanceToggle()].each { AbstractButton button ->
        int originalHeight = button.preferredSize.height as int
        int originalWidth = button.preferredSize.width as int
        button.font = button.font.deriveFont(36F)
        assertTrue(button.preferredSize.height > originalHeight)
        assertTrue(button.preferredSize.width > originalWidth)
      }
    }
  }

  @Test
  void advanceToggleHasTheSameWidthForBothStatesInBothLanguages() {
    withThemes {
      Locale original = I18n.instance.locale
      try {
        [Locale.ENGLISH, Locale.forLanguageTag('sv')].each { Locale locale ->
          I18n.instance.locale = locale
          JToggleButton toggle = VoucherToolbar.advanceToggle()
          [12F, 36F].each { float fontSize ->
            toggle.font = toggle.font.deriveFont(fontSize)
            def selectedSize = toggle.preferredSize
            toggle.selected = false
            assertEquals(selectedSize, toggle.preferredSize)
            toggle.selected = true
          }
        }
      } finally {
        I18n.instance.locale = original
      }
    }
  }

  @Test
  void selectedToggleIconHonorsADistinctThemeSelectionColor() {
    withThemes {
      UIDefaults defaults = UIManager.lookAndFeelDefaults
      Object original = defaults.get('ToggleButton.selectedForeground')
      try {
        defaults.put('ToggleButton.selectedForeground', Color.MAGENTA)
        JToggleButton toggle = VoucherToolbar.advanceToggle()
        assertIconColor(toggle, Color.MAGENTA)
        toggle.selected = false
        assertIconColor(toggle, toggle.foreground)
      } finally {
        defaults.put('ToggleButton.selectedForeground', original)
      }
    }
  }

  private static void withThemes(Closure<?> test) {
    SwingUtilities.invokeAndWait {
      LookAndFeel original = UIManager.lookAndFeel
      try {
        [new FlatLightLaf(), new FlatDarkLaf()].each { LookAndFeel theme ->
          UIManager.setLookAndFeel(theme)
          test.call()
        }
      } finally {
        UIManager.setLookAndFeel(original)
      }
    }
  }

  private static void assertIconColor(AbstractButton button, Color color) {
    Icon icon = button.enabled ? button.icon : button.disabledIcon
    BufferedImage image = new BufferedImage(icon.iconWidth, icon.iconHeight, BufferedImage.TYPE_INT_ARGB)
    Graphics2D graphics = image.createGraphics()
    try {
      icon.paintIcon(button, graphics, 0, 0)
    } finally {
      graphics.dispose()
    }
    assertTrue(containsColor(image, color), "Icon must render with ${color}".toString())
  }

  private static boolean containsColor(BufferedImage image, Color color) {
    for (int y = 0; y < image.height; y++) {
      for (int x = 0; x < image.width; x++) {
        Color pixel = new Color(image.getRGB(x, y), true)
        if (pixel.alpha > 0 && pixel.red == color.red && pixel.green == color.green && pixel.blue == color.blue) {
          return true
        }
      }
    }
    false
  }
}
