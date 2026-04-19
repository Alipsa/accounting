package unit.se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.*

import org.junit.jupiter.api.Test

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
    InputStream stream = getClass().getResourceAsStream('/splash.png')
    assertNotNull(stream, 'splash.png must be readable from the classpath')
    BufferedImage image = ImageIO.read(stream)
    assertEquals(480, image.width, 'splash width must be 480 px')
    assertEquals(300, image.height, 'splash height must be 300 px')
  }
}
