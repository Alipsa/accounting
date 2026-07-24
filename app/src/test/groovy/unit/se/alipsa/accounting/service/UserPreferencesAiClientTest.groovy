package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import se.alipsa.accounting.domain.AiClient

import java.util.prefs.Preferences

class UserPreferencesAiClientTest {

  private final Preferences node = Preferences.userRoot().node("test-ai-client-${UUID.randomUUID()}")
  private final UserPreferencesService service = new UserPreferencesService(node)

  @AfterEach
  void removePreferences() {
    node.removeNode()
  }

  @Test
  void savesAndRestoresTheSelectedAiClient() {
    service.aiClient = AiClient.KIMI

    assertEquals(AiClient.KIMI, service.aiClient)
  }

  @Test
  void returnsNullWhenNoAiClientHasBeenSelected() {
    assertNull(service.aiClient)
  }
}
