package se.alipsa.accounting.ui

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.extras.FlatSVGIcon
import com.formdev.flatlaf.util.UIScale

import se.alipsa.accounting.support.I18n

import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Insets
import java.util.function.BiFunction

import javax.swing.AbstractButton
import javax.swing.JButton
import javax.swing.JToggleButton
import javax.swing.UIManager

/** Shared sizing, accessible names and theme-aware SVG icons for voucher commands. */
final class VoucherToolbar {

  private VoucherToolbar() { }

  static JButton button(String iconName, String labelKey, Closure<?> action) {
    JButton button = new JButton()
    configure(button, iconName, labelKey)
    button.addActionListener { action.call() }
    button
  }

  static JToggleButton advanceToggle() {
    JToggleButton button = new JToggleButton()
    configure(button, 'new-after-save', 'voucherPanel.checkbox.advanceAfterSave')
    button.putClientProperty(FlatClientProperties.STYLE,
        'minimumWidth: 34; minimumHeight: 34; selectedBackground: $List.selectionBackground; selectedForeground: $List.selectionForeground')
    button.selected = true
    button
  }

  static void configure(AbstractButton button, String iconName, String labelKey) {
    FlatSVGIcon icon = new FlatSVGIcon("icons/voucher/${iconName}.svg", 16, 16)
    icon.setColorFilter(new FlatSVGIcon.ColorFilter({ Component component, Color ignored ->
      if (component instanceof JToggleButton && component.selected) {
        return UIManager.getColor('List.selectionForeground')
      }
      component?.foreground ?: UIManager.getColor('Label.foreground')
    } as BiFunction<Component, Color, Color>))
    button.icon = icon
    button.margin = new Insets(0, 0, 0, 0)
    button.preferredSize = UIScale.scale(new Dimension(34, 34))
    button.putClientProperty(FlatClientProperties.STYLE, 'minimumWidth: 34; minimumHeight: 34')
    updateLabel(button, labelKey)
  }

  static void updateLabel(AbstractButton button, String labelKey) {
    String label = I18n.instance.getString(labelKey)
    button.toolTipText = label
    button.accessibleContext.accessibleName = label
  }
}
