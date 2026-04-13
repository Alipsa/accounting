package unit.se.alipsa.accounting.support

import static org.junit.jupiter.api.Assertions.*

import org.junit.jupiter.api.Test

final class ResourceBundleCompletenessTest {

  @Test
  void swedishBundleContainsAllKeysFromEnglishBase() {
    ResourceBundle english = ResourceBundle.getBundle('i18n.messages', Locale.ENGLISH)
    ResourceBundle swedish = ResourceBundle.getBundle('i18n.messages', Locale.forLanguageTag('sv'))

    Set<String> englishKeys = english.keySet()
    Set<String> swedishKeys = swedish.keySet()

    Set<String> missingInSwedish = englishKeys - swedishKeys
    assertTrue(missingInSwedish.isEmpty(),
        "Keys missing in messages_sv.properties: ${missingInSwedish.sort().join(', ')}")
  }

  @Test
  void englishBaseContainsAllKeysFromSwedish() {
    ResourceBundle english = ResourceBundle.getBundle('i18n.messages', Locale.ENGLISH)
    ResourceBundle swedish = ResourceBundle.getBundle('i18n.messages', Locale.forLanguageTag('sv'))

    Set<String> englishKeys = english.keySet()
    Set<String> swedishKeys = swedish.keySet()

    Set<String> extraInSwedish = swedishKeys - englishKeys
    assertTrue(extraInSwedish.isEmpty(),
        "Keys in messages_sv.properties not in base messages.properties: ${extraInSwedish.sort().join(', ')}")
  }
}
