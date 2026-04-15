package se.alipsa.accounting.domain

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.FlatLightLaf


/**
 * Available theme modes for the application UI.
 */
enum ThemeMode {

  SYSTEM,
  LIGHT,
  DARK

  void apply() {
    switch (this) {
      case LIGHT:
        FlatLightLaf.setup()
        break
      case DARK:
        FlatDarkLaf.setup()
        break
      default:
        if (isOsDarkMode()) {
          FlatDarkLaf.setup()
        } else {
          FlatLightLaf.setup()
        }
        break
    }
  }

  void applyAndUpdateUI() {
    apply()
    FlatLaf.updateUI()
  }

  private static boolean isOsDarkMode() {
    Object hint = java.awt.Toolkit.defaultToolkit
        .getDesktopProperty('awt.os.isDarkMode')
    hint instanceof Boolean && hint
  }

  static ThemeMode fromName(String name) {
    if (name == null) {
      return SYSTEM
    }
    try {
      return valueOf(name)
    } catch (IllegalArgumentException ignored) {
      return SYSTEM
    }
  }
}
