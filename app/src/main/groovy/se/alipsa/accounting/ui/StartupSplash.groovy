package se.alipsa.accounting.ui

import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.MediaTracker
import java.awt.SplashScreen
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicReference

import javax.swing.BorderFactory
import javax.swing.ImageIcon
import javax.swing.JLabel
import javax.swing.JWindow
import javax.swing.SwingUtilities

/**
 * Shows a lightweight fallback splash while the GUI startup path initializes.
 */
final class StartupSplash implements AutoCloseable {

  private static final String SPLASH_RESOURCE = '/splash.png'
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
    ImageIcon icon = new ImageIcon(resource)
    if (icon.imageLoadStatus != MediaTracker.COMPLETE) {
      return null
    }
    JLabel imageLabel = new JLabel(icon)
    imageLabel.border = BorderFactory.createLineBorder(new Color(180, 180, 180))

    JWindow splashWindow = new JWindow()
    splashWindow.focusableWindowState = false
    splashWindow.alwaysOnTop = true
    splashWindow.contentPane.add(imageLabel)
    splashWindow.pack()
    splashWindow.setLocationRelativeTo(null)
    splashWindow.visible = true
    splashWindow
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
