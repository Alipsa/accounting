package se.alipsa.accounting.support

import java.awt.GraphicsEnvironment
import java.util.logging.Level
import java.util.logging.Logger

import javax.swing.JOptionPane
import javax.swing.SwingUtilities

/**
 * Surfaces exceptions thrown while handling a Swing event (e.g. a button click) that would
 * otherwise only be printed to stderr - invisible on a packaged Windows build launched without
 * a console window, where such a failure looks like the UI silently did nothing.
 *
 * AWT's EventDispatchThread looks up this class by name via the {@code sun.awt.exception.handler}
 * system property and instantiates it with a public no-arg constructor, so both must stay as-is.
 */
final class SwingExceptionHandler {

  private static final Logger log = Logger.getLogger(SwingExceptionHandler.name)

  static void install() {
    System.setProperty('sun.awt.exception.handler', SwingExceptionHandler.name)
  }

  /** Invoked by AWT via reflection - do not rename or change the signature. */
  void handle(Throwable throwable) {
    log.log(Level.SEVERE, 'Unhandled error on the event dispatch thread.', throwable)
    if (GraphicsEnvironment.headless) {
      return
    }
    String detail = throwable.message ?: throwable.class.simpleName
    String message = I18n.instance.format('alipsaAccounting.runtime.errorMessage', throwable.class.simpleName, detail)
    String title = I18n.instance.getString('alipsaAccounting.runtime.errorTitle')
    SwingUtilities.invokeLater {
      JOptionPane.showMessageDialog(null, message, title, JOptionPane.ERROR_MESSAGE)
    }
  }
}
