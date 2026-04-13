package unit.se.alipsa.accounting.support

import static org.junit.jupiter.api.Assertions.*

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import se.alipsa.accounting.support.I18n

import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener

final class I18nTest {

  private I18n i18n

  @BeforeEach
  void setUp() {
    i18n = new I18n()
    i18n.setLocale(Locale.ENGLISH)
  }

  @Test
  void getStringReturnsEnglishByDefault() {
    assertEquals('File', i18n.getString('mainFrame.menu.file'))
  }

  @Test
  void getStringReturnsSwedishAfterLocaleChange() {
    i18n.setLocale(Locale.forLanguageTag('sv'))
    assertEquals('Arkiv', i18n.getString('mainFrame.menu.file'))
  }

  @Test
  void getStringReturnsSameValueForSharedKey() {
    assertEquals('Alipsa Accounting', i18n.getString('app.name'))
  }

  @Test
  void formatInterpolatesParameters() {
    assertEquals('Hello World', i18n.format('test.greeting', 'World'))
  }

  @Test
  void formatReturnsSwedishWithParameters() {
    i18n.setLocale(Locale.forLanguageTag('sv'))
    assertEquals('Hej World', i18n.format('test.greeting', 'World'))
  }

  @Test
  void formatReturnsBracketedKeyWhenMissing() {
    assertEquals('[no.such.key]', i18n.format('no.such.key', 'arg'))
  }

  @Test
  void setLocaleFiresPropertyChangeEvent() {
    boolean fired = false
    Locale[] receivedLocales = [null, null]
    i18n.addLocaleChangeListener({ PropertyChangeEvent event ->
      fired = true
      receivedLocales[0] = event.oldValue as Locale
      receivedLocales[1] = event.newValue as Locale
    } as PropertyChangeListener)
    i18n.setLocale(Locale.forLanguageTag('sv'))
    assertTrue(fired)
    assertEquals(Locale.ENGLISH, receivedLocales[0])
    assertEquals(Locale.forLanguageTag('sv'), receivedLocales[1])
  }

  @Test
  void missingKeyFallsBackToBaseBundle() {
    i18n.setLocale(Locale.forLanguageTag('sv'))
    assertEquals('Alipsa Accounting', i18n.getString('app.name'))
  }

  @Test
  void missingKeyReturnsKeyInBrackets() {
    assertEquals('[no.such.key]', i18n.getString('no.such.key'))
  }

  @Test
  void removeLocaleChangeListenerStopsFiring() {
    int count = 0
    PropertyChangeListener listener = { count++ } as PropertyChangeListener
    i18n.addLocaleChangeListener(listener)
    i18n.setLocale(Locale.forLanguageTag('sv'))
    assertEquals(1, count)
    i18n.removeLocaleChangeListener(listener)
    i18n.setLocale(Locale.ENGLISH)
    assertEquals(1, count)
  }
}
