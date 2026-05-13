package se.alipsa.accounting.service

import se.alipsa.accounting.domain.ThemeMode

import java.util.prefs.Preferences

/**
 * Manages user-level preferences (as opposed to company settings).
 */
final class UserPreferencesService {

  private static final String LANGUAGE_KEY = 'ui.language'
  private static final String THEME_KEY = 'ui.theme'
  private static final String UPDATE_CHECK_ENABLED_KEY = 'update.autoCheckEnabled'
  private static final String LAST_ACTIVE_COMPANY_ID_KEY = 'company.lastActiveId'

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

  boolean isAutomaticUpdateCheckEnabled() {
    preferences.getBoolean(UPDATE_CHECK_ENABLED_KEY, true)
  }

  void setAutomaticUpdateCheckEnabled(boolean enabled) {
    if (enabled) {
      preferences.remove(UPDATE_CHECK_ENABLED_KEY)
    } else {
      preferences.putBoolean(UPDATE_CHECK_ENABLED_KEY, false)
    }
  }

  Long getLastActiveCompanyId() {
    long companyId = preferences.getLong(LAST_ACTIVE_COMPANY_ID_KEY, 0L)
    companyId > 0L ? companyId : null
  }

  void setLastActiveCompanyId(Long companyId) {
    if (companyId == null || companyId <= 0L) {
      preferences.remove(LAST_ACTIVE_COMPANY_ID_KEY)
      return
    }
    preferences.putLong(LAST_ACTIVE_COMPANY_ID_KEY, companyId)
  }
}
