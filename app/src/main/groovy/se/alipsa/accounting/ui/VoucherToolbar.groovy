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
    JButton button = new CommandButton()
    configure(button, iconName, labelKey)
    button.addActionListener { action.call() }
    button
  }

  static JButton saveButton(Closure<?> action) {
    JButton button = button('save', 'voucherPanel.button.save', action)
    button.text = I18n.instance.getString('voucherPanel.button.save')
    button.margin = new Insets(0, 10, 0, 10)
    button.putClientProperty(FlatClientProperties.STYLE,
        'background: $List.selectionBackground; foreground: $List.selectionForeground; focusedBackground: $List.selectionBackground')
    button
  }

  static JToggleButton advanceToggle() {
    JToggleButton button = new CommandToggleButton()
    configure(button, 'new-after-save', 'voucherPanel.toggle.advanceAfterSave')
    button.margin = new Insets(0, 8, 0, 8)
    button.addItemListener { updateAdvanceToggleLabel(button) }
    button.selected = true
    button
  }

  static void updateAdvanceToggleLabel(JToggleButton button) {
    updateLabel(button, 'voucherPanel.toggle.advanceAfterSave')
    String state = button.selected ? 'on' : 'off'
    button.text = I18n.instance.getString("voucherPanel.toggle.advanceAfterSave.${state}")
  }

  static void configure(AbstractButton button, String iconName, String labelKey) {
    FlatSVGIcon icon = new FlatSVGIcon("icons/voucher/${iconName}.svg", 16, 16)
    icon.setColorFilter(new FlatSVGIcon.ColorFilter({ Component component, Color ignored ->
      if (component != null && !component.enabled) {
        return UIManager.getColor('Button.disabledText')
      }
      if (component instanceof JToggleButton && component.selected) {
        return UIManager.getColor('ToggleButton.selectedForeground') ?: component.foreground
      }
      component?.foreground ?: UIManager.getColor('Label.foreground')
    } as BiFunction<Component, Color, Color>))
    button.icon = icon
    // The filter already supplies the disabled color; avoid applying a second gray filter.
    button.disabledIcon = icon
    button.margin = new Insets(0, 0, 0, 0)
    updateLabel(button, labelKey)
  }

  static void updateLabel(AbstractButton button, String labelKey) {
    String label = I18n.instance.getString(labelKey)
    button.toolTipText = label
    button.accessibleContext.accessibleName = label
  }

  private static Dimension commandSize(Dimension preferred) {
    int minimum = UIScale.scale(34)
    new Dimension(Math.max(minimum, preferred.width as int), Math.max(minimum, preferred.height as int))
  }

  /** Recompute command sizing from the current font and UI scale on every layout. */
  private static final class CommandButton extends JButton {

    @Override
    Dimension getPreferredSize() {
      commandSize(super.getPreferredSize())
    }
  }

  /** Keep labelled mode controls aligned with commands without fixing their text width. */
  private static final class CommandToggleButton extends JToggleButton {

    @Override
    Dimension getPreferredSize() {
      commandSize(super.getPreferredSize())
    }
  }
}
