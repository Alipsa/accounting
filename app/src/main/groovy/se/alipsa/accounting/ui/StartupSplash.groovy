package se.alipsa.accounting.ui

import se.alipsa.accounting.support.I18n

import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.MediaTracker
import java.awt.RenderingHints
import java.awt.SplashScreen
import java.awt.image.BufferedImage
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicReference

import javax.imageio.ImageIO
import javax.swing.ImageIcon
import javax.swing.JLabel
import javax.swing.JWindow
import javax.swing.SwingUtilities

/**
 * Shows a lightweight fallback splash while the GUI startup path initializes.
 */
final class StartupSplash implements AutoCloseable {

  private static final String SPLASH_RESOURCE = '/splash.png'
  private static final int TEXT_BAND_HEIGHT = 56
  private static final StartupSplash NONE = new StartupSplash(null, null)

  private final JWindow window
  private final SplashScreen nativeSplash
  private boolean closed

  private StartupSplash(JWindow window, SplashScreen nativeSplash) {
    this.window = window
    this.nativeSplash = nativeSplash
  }

  static StartupSplash showIfPossible(boolean enabled) {
    if (!enabled || GraphicsEnvironment.headless) {
      return NONE
    }
    try {
      SplashScreen activeSplash = SplashScreen.getSplashScreen()
      if (activeSplash != null) {
        localizeNativeSplash(activeSplash)
        return new StartupSplash(null, activeSplash)
      }

      URL resource = StartupSplash.getResource(SPLASH_RESOURCE)
      if (resource == null) {
        return NONE
      }

      AtomicReference<JWindow> windowReference = new AtomicReference<>()
      Runnable showWindow = { ->
        windowReference.set(createWindow(resource))
      } as Runnable
      if (SwingUtilities.isEventDispatchThread()) {
        showWindow.run()
      } else {
        SwingUtilities.invokeAndWait(showWindow)
      }
      return new StartupSplash(windowReference.get(), null)
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt()
      return NONE
    } catch (InvocationTargetException ignored) {
      return NONE
    } catch (IllegalStateException ignored) {
      return NONE
    } catch (UnsupportedOperationException ignored) {
      return NONE
    }
  }

  void close() {
    if (closed) {
      return
    }
    closed = true
    closeNativeSplash()
    if (window == null) {
      return
    }
    Runnable disposeWindow = { -> window.dispose() } as Runnable
    if (SwingUtilities.isEventDispatchThread()) {
      disposeWindow.run()
      return
    }
    SwingUtilities.invokeLater(disposeWindow)
  }

  private static JWindow createWindow(URL resource) {
    BufferedImage image = ImageIO.read(resource)
    if (image == null) {
      return null
    }
    Graphics2D graphics = image.createGraphics()
    try {
      drawStartupMessage(graphics, image.width, image.height)
    } finally {
      graphics.dispose()
    }
    ImageIcon icon = new ImageIcon(image)
    if (icon.imageLoadStatus != MediaTracker.COMPLETE) {
      return null
    }
    JLabel imageLabel = new JLabel(icon)

    JWindow splashWindow = new JWindow()
    splashWindow.focusableWindowState = false
    splashWindow.alwaysOnTop = true
    splashWindow.contentPane.add(imageLabel)
    splashWindow.pack()
    splashWindow.setLocationRelativeTo(null)
    splashWindow.visible = true
    splashWindow
  }

  static String startupMessage() {
    I18n.instance.getString('startupSplash.message')
  }

  private static void localizeNativeSplash(SplashScreen splash) {
    Graphics2D graphics = splash.createGraphics()
    if (graphics == null) {
      return
    }
    try {
      Dimension size = splash.size
      int width = size.width as int
      int height = size.height as int
      drawStartupMessage(graphics, width, height)
      splash.update()
    } finally {
      graphics.dispose()
    }
  }

  private static void drawStartupMessage(Graphics2D graphics, int width, int height) {
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    graphics.color = Color.WHITE
    graphics.fillRect(0, height - TEXT_BAND_HEIGHT, width, TEXT_BAND_HEIGHT)
    graphics.color = new Color(60, 60, 60)
    graphics.font = new Font(Font.SANS_SERIF, Font.PLAIN, 22)
    FontMetrics metrics = graphics.fontMetrics
    String text = startupMessage()
    int textX = (width - metrics.stringWidth(text)).intdiv(2)
    int textY = height - TEXT_BAND_HEIGHT + ((TEXT_BAND_HEIGHT - metrics.height).intdiv(2)) + metrics.ascent
    graphics.drawString(text, textX, textY)
  }

  private void closeNativeSplash() {
    if (nativeSplash == null) {
      return
    }
    try {
      nativeSplash.close()
    } catch (IllegalStateException ignored) {
      // The JVM closes the native splash automatically when the first window appears.
    }
  }
}
