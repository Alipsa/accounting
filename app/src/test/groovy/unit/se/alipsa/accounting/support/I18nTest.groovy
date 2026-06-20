package unit.se.alipsa.accounting.support

import static org.junit.jupiter.api.Assertions.*

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import se.alipsa.accounting.support.I18n

import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener

import javax.swing.JComponent

final class I18nTest {

  private I18n i18n
  private Locale previousDefaultLocale
  private Locale previousComponentLocale

  @BeforeEach
  void setUp() {
    previousDefaultLocale = Locale.default
    previousComponentLocale = JComponent.defaultLocale
    i18n = new I18n()
    i18n.setLocale(Locale.ENGLISH)
  }

  @AfterEach
  void tearDown() {
    JComponent.setDefaultLocale(previousComponentLocale)
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
    assertEquals('Fiscal year 2024 created.', i18n.format('fiscalYearPanel.message.created', '2024'))
  }

  @Test
  void formatReturnsSwedishWithParameters() {
    i18n.setLocale(Locale.forLanguageTag('sv'))
    assertEquals('R\u00e4kenskaps\u00e5ret 2024 skapades.', i18n.format('fiscalYearPanel.message.created', '2024'))
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
  void setLocaleUpdatesSwingDefaultWithoutChangingJvmDefault() {
    Locale swedish = Locale.forLanguageTag('sv')

    i18n.setLocale(swedish)

    assertEquals(previousDefaultLocale, Locale.default)
    assertEquals(swedish, JComponent.defaultLocale)
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
