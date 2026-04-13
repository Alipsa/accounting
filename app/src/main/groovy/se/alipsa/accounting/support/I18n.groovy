package se.alipsa.accounting.support

import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import java.text.MessageFormat
import java.util.logging.Logger

/**
 * Manages locale and translatable string lookups with runtime switching support.
 */
final class I18n {

  private static final Logger log = Logger.getLogger(I18n.name)
  private static final String BUNDLE_BASE_NAME = 'i18n.messages'

  @SuppressWarnings('PropertyName')
  static final I18n instance = new I18n()

  private final PropertyChangeSupport changeSupport = new PropertyChangeSupport(this)
  private volatile Locale currentLocale
  private volatile ResourceBundle bundle

  I18n(Locale locale = Locale.ENGLISH) {
    this.currentLocale = locale
    this.bundle = loadBundle(locale)
  }

  String getString(String key) {
    try {
      bundle.getString(key)
    } catch (MissingResourceException ignored) {
      log.warning("Missing i18n key: ${key}")
      "[${key}]"
    }
  }

  String format(String key, Object... args) {
    try {
      String pattern = bundle.getString(key)
      MessageFormat.format(pattern, args)
    } catch (MissingResourceException ignored) {
      log.warning("Missing i18n key: ${key}")
      "[${key}]"
    }
  }

  Locale getLocale() {
    currentLocale
  }

  void setLocale(Locale locale) {
    Locale oldLocale = currentLocale
    currentLocale = locale
    bundle = loadBundle(locale)
    changeSupport.firePropertyChange('locale', oldLocale, locale)
  }

  void addLocaleChangeListener(PropertyChangeListener listener) {
    changeSupport.addPropertyChangeListener('locale', listener)
  }

  void removeLocaleChangeListener(PropertyChangeListener listener) {
    changeSupport.removePropertyChangeListener('locale', listener)
  }

  private static ResourceBundle loadBundle(Locale locale) {
    ResourceBundle.getBundle(BUNDLE_BASE_NAME, locale, new ResourceBundle.Control() {
      @Override
      long getTimeToLive(String baseName, Locale loc) {
        ResourceBundle.Control.TTL_DONT_CACHE
      }
    })
  }
}
