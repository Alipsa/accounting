package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.assertEquals

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import se.alipsa.accounting.support.I18n

import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener

/**
 * Verifies ListenerLifecycle in isolation, without needing a displayable window: no test in
 * this project ever shows a real JFrame (CI runners aren't guaranteed a display), so
 * addNotify/removeNotify can't be exercised here. dispose() is the path components must use
 * when constructed but never realized on screen - exactly what UI integration tests do - and
 * is what this test proves.
 */
class ListenerLifecycleTest {

  private Locale previousLocale
  private Locale otherLocale

  @BeforeEach
  void setUp() {
    previousLocale = I18n.instance.locale
    // The suite runs many tests that flip the global I18n locale; pick whichever of these two
    // differs from whatever locale an earlier test in this JVM left behind, so setLocale below
    // is guaranteed to actually change the value and fire a PropertyChangeEvent (setLocale is a
    // no-op when old.equals(new)).
    otherLocale = previousLocale == Locale.forLanguageTag('sv') ? Locale.ENGLISH : Locale.forLanguageTag('sv')
  }

  @AfterEach
  void tearDown() {
    I18n.instance.setLocale(previousLocale)
  }

  @Test
  void registeredProbeReactsToLocaleChanges() {
    Probe probe = new Probe()
    probe.registerListenersOnce()

    I18n.instance.setLocale(otherLocale)

    assertEquals(1, probe.localeChangeCount)
  }

  @Test
  void registerListenersOnceIsIdempotent() {
    Probe probe = new Probe()
    probe.registerListenersOnce()
    probe.registerListenersOnce()
    probe.registerListenersOnce()

    I18n.instance.setLocale(otherLocale)

    assertEquals(1, probe.localeChangeCount)
  }

  @Test
  void disposeStopsReactingEvenWhenNeverAddedToAWindow() {
    Probe probe = new Probe()
    probe.registerListenersOnce()

    probe.dispose()
    I18n.instance.setLocale(otherLocale)

    assertEquals(0, probe.localeChangeCount)
  }

  @Test
  void disposeIsIdempotentAndSafeWithoutPriorRegistration() {
    Probe probe = new Probe()

    probe.dispose()
    probe.dispose()

    probe.registerListenersOnce()
    probe.dispose()
    probe.dispose()
    I18n.instance.setLocale(otherLocale)

    assertEquals(0, probe.localeChangeCount)
  }

  private static final class Probe implements PropertyChangeListener, ListenerLifecycle {

    int localeChangeCount = 0

    @Override
    void propertyChange(PropertyChangeEvent event) {
      localeChangeCount++
    }

    @Override
    void doRegisterListeners() {
      I18n.instance.addLocaleChangeListener(this)
    }

    @Override
    void doUnregisterListeners() {
      I18n.instance.removeLocaleChangeListener(this)
    }
  }
}
