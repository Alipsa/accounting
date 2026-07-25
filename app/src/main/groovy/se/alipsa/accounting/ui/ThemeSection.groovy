package se.alipsa.accounting.ui

import se.alipsa.accounting.domain.ThemeMode
import se.alipsa.accounting.service.UserPreferencesService
import se.alipsa.accounting.support.I18n

import java.awt.FlowLayout

import javax.swing.ButtonGroup
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton

/** Settings row letting the user choose the application theme. */
final class ThemeSection {

  private final UserPreferencesService userPreferencesService
  private final Runnable onChange
  private final JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0))
  private final JLabel label = new JLabel()
  private final JRadioButton systemButton = new JRadioButton()
  private final JRadioButton lightButton = new JRadioButton()
  private final JRadioButton darkButton = new JRadioButton()

  ThemeSection(UserPreferencesService userPreferencesService, Runnable onChange) {
    this.userPreferencesService = userPreferencesService
    this.onChange = onChange
    ButtonGroup group = new ButtonGroup()
    group.add(systemButton)
    group.add(lightButton)
    group.add(darkButton)
    selectButtonFor(userPreferencesService.getTheme())
    systemButton.addActionListener { switchTheme(ThemeMode.SYSTEM) }
    lightButton.addActionListener { switchTheme(ThemeMode.LIGHT) }
    darkButton.addActionListener { switchTheme(ThemeMode.DARK) }
    panel.add(label)
    panel.add(systemButton)
    panel.add(lightButton)
    panel.add(darkButton)
    applyLocale()
  }

  JPanel getPanel() {
    panel
  }

  void applyLocale() {
    label.text = I18n.instance.getString('settings.label.theme')
    systemButton.text = I18n.instance.getString('settings.theme.system')
    lightButton.text = I18n.instance.getString('settings.theme.light')
    darkButton.text = I18n.instance.getString('settings.theme.dark')
  }

  private void switchTheme(ThemeMode mode) {
    userPreferencesService.setTheme(mode)
    ThemeApplier.applyAndUpdateUI(mode)
    onChange?.run()
  }

  private void selectButtonFor(ThemeMode mode) {
    switch (mode) {
      case ThemeMode.LIGHT:
        lightButton.selected = true
        break
      case ThemeMode.DARK:
        darkButton.selected = true
        break
      default:
        systemButton.selected = true
        break
    }
  }
}
