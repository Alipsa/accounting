package se.alipsa.accounting.service

import se.alipsa.accounting.domain.ThemeMode

import java.util.prefs.Preferences

/**
 * Manages user-level preferences (as opposed to company settings).
 */
final class UserPreferencesService {

  private static final String LANGUAGE_KEY = 'ui.language'
  private static final String THEME_KEY = 'ui.theme'

  private final Preferences preferences = Preferences.userNodeForPackage(UserPreferencesService)

  Locale getLanguage() {
    String tag = preferences.get(LANGUAGE_KEY, null)
    tag != null ? Locale.forLanguageTag(tag) : null
  }

  void setLanguage(Locale locale) {
    preferences.put(LANGUAGE_KEY, locale.toLanguageTag())
  }

  ThemeMode getTheme() {
    String name = preferences.get(THEME_KEY, null)
    ThemeMode.fromName(name)
  }

  void setTheme(ThemeMode mode) {
    if (mode == null || mode == ThemeMode.SYSTEM) {
      preferences.remove(THEME_KEY)
    } else {
      preferences.put(THEME_KEY, mode.name())
    }
  }
}
