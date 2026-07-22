package se.alipsa.accounting.service

import se.alipsa.accounting.domain.ThemeMode

import java.security.SecureRandom
import java.util.logging.Level
import java.util.logging.Logger
import java.util.prefs.BackingStoreException
import java.util.prefs.Preferences

/**
 * Manages user-level preferences (as opposed to company settings).
 */
final class UserPreferencesService {

  private static final Logger log = Logger.getLogger(UserPreferencesService.name)
  private static final String LANGUAGE_KEY = 'ui.language'
  private static final String THEME_KEY = 'ui.theme'
  private static final String UPDATE_CHECK_ENABLED_KEY = 'update.autoCheckEnabled'
  private static final String LAST_ACTIVE_COMPANY_ID_KEY = 'company.lastActiveId'
  private static final String LAST_ACTIVE_FISCAL_YEAR_ID_KEY = 'fiscalYear.lastActiveId'
  private static final String DATA_LOCATION_KEY = 'data.location'
  private static final String PENDING_MIGRATION_TARGET_KEY = 'data.pendingMigrationTarget'
  private static final String PENDING_MIGRATION_MOVE_KEY = 'data.pendingMigrationMove'
  private static final String LAST_SIE_IMPORT_DIRECTORY_KEY = 'sie.import.lastDirectory'
  private static final String MCP_TOKEN_KEY = 'mcp.token'

  private final Preferences preferences

  UserPreferencesService() {
    this(Preferences.userNodeForPackage(UserPreferencesService))
  }

  /**
   * Visible for tests, so they can use an isolated node instead of the real, machine-global
   * preferences store shared with the packaged application.
   */
  UserPreferencesService(Preferences preferences) {
    this.preferences = preferences
    log.info("Using preferences node ${preferences.absolutePath()} (user.home=${System.getProperty('user.home')}, java.util.prefs.userRoot=${System.getProperty('java.util.prefs.userRoot', '<default>')}).")
  }

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
    Long activeCompanyId = companyId > 0L ? companyId : null
    log.info("Read ${LAST_ACTIVE_COMPANY_ID_KEY}=${activeCompanyId} from ${preferences.absolutePath()}.")
    activeCompanyId
  }

  void setLastActiveCompanyId(Long companyId) {
    if (companyId == null || companyId <= 0L) {
      preferences.remove(LAST_ACTIVE_COMPANY_ID_KEY)
    } else {
      preferences.putLong(LAST_ACTIVE_COMPANY_ID_KEY, companyId)
    }
    flushActiveContext()
    log.info("Saved ${LAST_ACTIVE_COMPANY_ID_KEY}=${companyId} to ${preferences.absolutePath()}.")
  }

  Long getLastActiveFiscalYearId() {
    long fiscalYearId = preferences.getLong(LAST_ACTIVE_FISCAL_YEAR_ID_KEY, 0L)
    Long activeFiscalYearId = fiscalYearId > 0L ? fiscalYearId : null
    log.info("Read ${LAST_ACTIVE_FISCAL_YEAR_ID_KEY}=${activeFiscalYearId} from ${preferences.absolutePath()}.")
    activeFiscalYearId
  }

  void setLastActiveFiscalYearId(Long fiscalYearId) {
    if (fiscalYearId == null || fiscalYearId <= 0L) {
      preferences.remove(LAST_ACTIVE_FISCAL_YEAR_ID_KEY)
    } else {
      preferences.putLong(LAST_ACTIVE_FISCAL_YEAR_ID_KEY, fiscalYearId)
    }
    flushActiveContext()
    log.info("Saved ${LAST_ACTIVE_FISCAL_YEAR_ID_KEY}=${fiscalYearId} to ${preferences.absolutePath()}.")
  }

  private void flushActiveContext() {
    try {
      preferences.flush()
      log.info("Flushed active context preferences to ${preferences.absolutePath()}.")
    } catch (BackingStoreException exception) {
      log.log(Level.WARNING, 'Unable to save the last active company and fiscal year.', exception)
    }
  }

  String getDataLocation() {
    preferences.get(DATA_LOCATION_KEY, null)
  }

  void setDataLocation(String path) {
    preferences.put(DATA_LOCATION_KEY, path)
  }

  void clearDataLocation() {
    preferences.remove(DATA_LOCATION_KEY)
  }

  String getPendingMigrationTarget() {
    preferences.get(PENDING_MIGRATION_TARGET_KEY, null)
  }

  boolean isPendingMigrationMove() {
    preferences.getBoolean(PENDING_MIGRATION_MOVE_KEY, false)
  }

  void setPendingMigration(String target, boolean move) {
    preferences.put(PENDING_MIGRATION_TARGET_KEY, target)
    preferences.putBoolean(PENDING_MIGRATION_MOVE_KEY, move)
  }

  void clearPendingMigration() {
    preferences.remove(PENDING_MIGRATION_TARGET_KEY)
    preferences.remove(PENDING_MIGRATION_MOVE_KEY)
  }

  String getLastSieImportDirectory() {
    preferences.get(LAST_SIE_IMPORT_DIRECTORY_KEY, null)
  }

  void setLastSieImportDirectory(String path) {
    if (path?.trim()) {
      preferences.put(LAST_SIE_IMPORT_DIRECTORY_KEY, path)
    } else {
      preferences.remove(LAST_SIE_IMPORT_DIRECTORY_KEY)
    }
  }

  String ensureMcpToken() {
    String token = preferences.get(MCP_TOKEN_KEY, null)
    if (token) {
      return token
    }
    regenerateMcpToken()
  }

  String regenerateMcpToken() {
    byte[] bytes = new byte[32]
    new SecureRandom().nextBytes(bytes)
    String token = bytes.encodeBase64Url().toString().replace('=', '')
    preferences.put(MCP_TOKEN_KEY, token)
    token
  }
}
