package unit.se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.*

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable

import se.alipsa.accounting.support.I18n
import se.alipsa.accounting.ui.StartupSplash

import java.awt.image.BufferedImage

import javax.imageio.ImageIO

final class SplashScreenTest {

  @Test
  void splashImageIsOnClasspath() {
    URL resource = getClass().getResource('/splash.png')
    assertNotNull(resource, 'splash.png must be bundled as a classpath resource')
  }

  @Test
  void splashImageHasCorrectDimensions() {
    getClass().getResourceAsStream('/splash.png').withStream { InputStream stream ->
      assertNotNull(stream, 'splash.png must be readable from the classpath')
      BufferedImage image = ImageIO.read(stream)
      assertEquals(512, image.width, 'splash width must match the logo width')
      assertEquals(568, image.height, 'splash height must include the logo and text band')
    }
  }

  @Test
  void disabledFallbackSplashCanBeClosed() {
    assertDoesNotThrow({
      StartupSplash.showIfPossible(false).close()
    } as Executable)
  }

  @Test
  void startupMessageUsesSelectedLanguage() {
    Locale originalLocale = I18n.instance.locale
    try {
      I18n.instance.setLocale(Locale.forLanguageTag('sv'))
      assertEquals('Startar Alipsa Bokföring', StartupSplash.startupMessage())
    } finally {
      I18n.instance.setLocale(originalLocale)
    }
  }
}
