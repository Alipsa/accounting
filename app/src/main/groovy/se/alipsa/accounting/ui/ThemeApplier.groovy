package se.alipsa.accounting.ui

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.FlatLightLaf

import se.alipsa.accounting.domain.ThemeMode

import java.util.concurrent.TimeUnit
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

  private static final long SUBPROCESS_TIMEOUT_SECONDS = 2

  private static boolean isWindowsDarkMode() {
    try {
      Process process = ['reg', 'query',
          'HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize',
          '/v', 'AppsUseLightTheme'].execute()
      if (!process.waitFor(SUBPROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return false
      }
      return process.text.contains('0x0')
    } catch (Exception exception) {
      log.log(Level.FINE, 'Could not read Windows dark mode setting.', exception)
      return false
    }
  }

  private static boolean isLinuxDarkMode() {
    String desktop = System.getenv('XDG_CURRENT_DESKTOP')?.toLowerCase() ?: ''
    if (desktop.contains('kde')) {
      return isKdeDarkMode()
    }
    if (desktop.contains('xfce')) {
      return isXfceDarkMode()
    }
    isGnomeDarkMode()
  }

  private static boolean isGnomeDarkMode() {
    try {
      Process process = ['gsettings', 'get', 'org.gnome.desktop.interface', 'color-scheme'].execute()
      if (!process.waitFor(SUBPROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return false
      }
      String output = process.text?.trim()
      if (output?.contains('dark')) {
        return true
      }
      Process gtkProcess = ['gsettings', 'get', 'org.gnome.desktop.interface', 'gtk-theme'].execute()
      if (!gtkProcess.waitFor(SUBPROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        gtkProcess.destroyForcibly()
        return false
      }
      return gtkProcess.text?.toLowerCase()?.contains('dark')
    } catch (Exception exception) {
      log.log(Level.FINE, 'Could not read GNOME dark mode setting.', exception)
      return false
    }
  }

  private static boolean isKdeDarkMode() {
    try {
      File kdeglobals = new File(System.getProperty('user.home'), '.config/kdeglobals')
      if (!kdeglobals.isFile()) {
        return false
      }
      return kdeglobals.text.readLines().any { String line ->
        line.trim().toLowerCase().matches(/colorscheme\s*=.*dark.*/)
      }
    } catch (Exception exception) {
      log.log(Level.FINE, 'Could not read KDE dark mode setting.', exception)
      return false
    }
  }

  private static boolean isXfceDarkMode() {
    try {
      Process process = ['xfconf-query', '-c', 'xsettings', '-p', '/Net/ThemeName'].execute()
      if (!process.waitFor(SUBPROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return false
      }
      return process.text?.toLowerCase()?.contains('dark')
    } catch (Exception exception) {
      log.log(Level.FINE, 'Could not read XFCE dark mode setting.', exception)
      return false
    }
  }
}
