package se.alipsa.accounting

import groovy.transform.CompileStatic

import se.alipsa.accounting.service.DatabaseService
import se.alipsa.accounting.support.LoggingConfigurer
import se.alipsa.accounting.ui.MainFrame

import java.awt.GraphicsEnvironment
import java.util.logging.Level
import java.util.logging.Logger

import javax.swing.JOptionPane
import javax.swing.SwingUtilities

/**
 * Application entry point for Alipsa Accounting.
 */
@CompileStatic
final class AlipsaAccounting {

    private static final Logger log = Logger.getLogger(AlipsaAccounting.name)

    private AlipsaAccounting() {
    }

    static void main(String[] args) {
        try {
            LoggingConfigurer.configure()
            DatabaseService.instance.initialize()
            SwingUtilities.invokeLater {
                MainFrame mainFrame = new MainFrame()
                mainFrame.display()
            }
        } catch (Throwable throwable) {
            log.log(Level.SEVERE, 'Failed to start Alipsa Accounting.', throwable)
            showStartupError(throwable)
            throw throwable
        }
    }

    private static void showStartupError(Throwable throwable) {
        if (GraphicsEnvironment.headless) {
            return
        }
        String detail = throwable.message ?: 'Unknown startup failure.'
        String message = """Alipsa Accounting kunde inte starta.

${throwable.class.simpleName}: ${detail}

Se loggfilen för mer information."""
        if (SwingUtilities.isEventDispatchThread()) {
            JOptionPane.showMessageDialog(null, message, 'Alipsa Accounting', JOptionPane.ERROR_MESSAGE)
            return
        }
        SwingUtilities.invokeAndWait {
            JOptionPane.showMessageDialog(null, message, 'Alipsa Accounting', JOptionPane.ERROR_MESSAGE)
        }
    }
}
