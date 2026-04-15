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
        FlatLaf.setup(new FlatLightLaf())
        FlatLaf.setUseNativeWindowDecorations(true)
        break
    }
  }

  void applyAndUpdateUI() {
    apply()
    FlatLaf.updateUI()
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
