package se.alipsa.accounting.ui

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.FlatLightLaf

import se.alipsa.accounting.domain.ThemeMode

import java.util.logging.Level
import java.util.logging.Logger

/**
 * Applies the selected {@link ThemeMode} using FlatLaf.
 */
final class ThemeApplier {

  private static final Logger log = Logger.getLogger(ThemeApplier.name)

  private ThemeApplier() {
  }

  static void apply(ThemeMode mode) {
    switch (mode) {
      case ThemeMode.LIGHT:
        FlatLightLaf.setup()
        break
      case ThemeMode.DARK:
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

  static void applyAndUpdateUI(ThemeMode mode) {
    apply(mode)
    FlatLaf.updateUI()
  }

  private static boolean isOsDarkMode() {
    // macOS: AWT desktop property
    Object macHint = java.awt.Toolkit.defaultToolkit
        .getDesktopProperty('awt.os.isDarkMode')
    if (macHint instanceof Boolean) {
      return macHint
    }
    // Windows 10/11: registry key for app theme
    if (com.formdev.flatlaf.util.SystemInfo.isWindows) {
      return isWindowsDarkMode()
    }
    // Linux: check common desktop environment settings
    if (com.formdev.flatlaf.util.SystemInfo.isLinux) {
      return isLinuxDarkMode()
    }
    false
  }

  private static boolean isWindowsDarkMode() {
    try {
      Process process = ['reg', 'query',
          'HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize',
          '/v', 'AppsUseLightTheme'].execute()
      String output = process.text
      process.waitFor()
      return output.contains('0x0')
    } catch (Exception exception) {
      log.log(Level.FINE, 'Could not read Windows dark mode setting.', exception)
      return false
    }
  }

  private static boolean isLinuxDarkMode() {
    try {
      Process process = ['gsettings', 'get', 'org.gnome.desktop.interface', 'color-scheme'].execute()
      String output = process.text?.trim()
      process.waitFor()
      if (output?.contains('dark')) {
        return true
      }
      Process gtkProcess = ['gsettings', 'get', 'org.gnome.desktop.interface', 'gtk-theme'].execute()
      String gtkOutput = gtkProcess.text?.trim()
      gtkProcess.waitFor()
      return gtkOutput?.toLowerCase()?.contains('dark')
    } catch (Exception exception) {
      log.log(Level.FINE, 'Could not read Linux dark mode setting.', exception)
      return false
    }
  }
}
