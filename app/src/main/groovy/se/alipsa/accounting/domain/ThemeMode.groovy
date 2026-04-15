package se.alipsa.accounting.domain

import java.util.logging.Level
import java.util.logging.Logger

/**
 * Available theme modes for the application UI.
 */
enum ThemeMode {

  SYSTEM,
  LIGHT,
  DARK

  private static final Logger log = Logger.getLogger(ThemeMode.name)

  static ThemeMode fromName(String name) {
    if (name == null) {
      return SYSTEM
    }
    try {
      return valueOf(name)
    } catch (IllegalArgumentException ignored) {
      log.log(Level.WARNING, "Unknown theme preference ''{0}'', falling back to SYSTEM.", name)
      return SYSTEM
    }
  }
}
