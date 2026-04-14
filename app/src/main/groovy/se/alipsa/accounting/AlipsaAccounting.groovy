package se.alipsa.accounting

import se.alipsa.accounting.service.DatabaseService
import se.alipsa.accounting.service.StartupVerificationReport
import se.alipsa.accounting.service.StartupVerificationService
import se.alipsa.accounting.service.UserPreferencesService
import se.alipsa.accounting.support.AppPaths
import se.alipsa.accounting.support.I18n
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
final class AlipsaAccounting {

  private static final Logger log = Logger.getLogger(AlipsaAccounting.name)
  private static final String VERIFY_LAUNCH_ARGUMENT = '--verify-launch'
  private static final String VERSION_ARGUMENT = '--version'
  private static final String HOME_ARGUMENT_PREFIX = '--home='

  private AlipsaAccounting() {
  }

  static void main(String[] args) {
    StartupOptions options = StartupOptions.parse(args ?: new String[0])
    if (options.applicationHomeOverride != null) {
      System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, options.applicationHomeOverride)
    }
    if (options.versionRequested) {
      System.out.println(versionLine())
      return
    }
    I18n.instance.setLocale(Locale.getDefault())
    try {
      LoggingConfigurer.configure()
      DatabaseService.instance.initialize()
      Locale savedLanguage = new UserPreferencesService().getLanguage()
      if (savedLanguage != null) {
        I18n.instance.setLocale(savedLanguage)
      }
      StartupVerificationReport startupReport = new StartupVerificationService().verify()
      if (options.verifyLaunchRequested) {
        failOnStartupErrors(startupReport)
        String version = AlipsaAccounting.package?.implementationVersion
        if (!version) {
          log.warning('JAR manifest saknar Implementation-Version — paketeringen kan vara felaktig.')
        }
        if (!startupReport.warnings.isEmpty()) {
          log.warning("Launch verification completed with warnings: ${startupReport.warnings.join(' | ')}")
        }
        System.out.println("Launch verification OK: ${versionLine()} [home=${AppPaths.applicationHome()}]")
        // Release the H2 file handles so the caller (tests, packaging verification) can delete
        // the verification home directory on Windows.
        DatabaseService.instance.shutdown()
        return
      }
      if (!startupReport.ok || !startupReport.warnings.isEmpty()) {
        showStartupVerificationWarning(startupReport)
      }
      SwingUtilities.invokeLater {
        MainFrame mainFrame = new MainFrame()
        mainFrame.display()
      }
    } catch (Exception exception) {
      log.log(Level.SEVERE, 'Failed to start Alipsa Accounting.', exception)
      if (options.interactive) {
        showStartupError(exception)
      } else {
        System.err.println("Failed to start Alipsa Accounting: ${exception.message ?: exception.class.simpleName}")
      }
      throw exception
    }
  }

  private static void failOnStartupErrors(StartupVerificationReport report) {
    if (report.ok) {
      return
    }
    throw new IllegalStateException("Startup verification failed: ${report.errors.join(' | ')}")
  }

  private static String versionLine() {
    String version = AlipsaAccounting.package?.implementationVersion ?: 'dev'
    "Alipsa Accounting ${version}"
  }

  private static void showStartupError(Throwable throwable) {
    if (GraphicsEnvironment.headless) {
      return
    }
    String detail = throwable.message ?: 'Unknown startup failure.'
    String message = I18n.instance.format('alipsaAccounting.startup.errorMessage', throwable.class.simpleName, detail)
    String title = I18n.instance.getString('alipsaAccounting.startup.errorTitle')
    if (SwingUtilities.isEventDispatchThread()) {
      JOptionPane.showMessageDialog(null, message, title, JOptionPane.ERROR_MESSAGE)
      return
    }
    SwingUtilities.invokeAndWait {
      JOptionPane.showMessageDialog(null, message, title, JOptionPane.ERROR_MESSAGE)
    }
  }

  private static void showStartupVerificationWarning(StartupVerificationReport report) {
    if (GraphicsEnvironment.headless) {
      return
    }
    List<String> rows = []
    if (!report.errors.isEmpty()) {
      rows << I18n.instance.getString('alipsaAccounting.startup.verificationErrors')
      rows.addAll(report.errors.take(5).collect { String error -> "- ${error}".toString() })
    }
    if (!report.warnings.isEmpty()) {
      rows << I18n.instance.getString('alipsaAccounting.startup.verificationWarnings')
      rows.addAll(report.warnings.take(5).collect { String warning -> "- ${warning}".toString() })
    }
    String message = I18n.instance.format('alipsaAccounting.startup.verificationMessage', rows.join('\n'))
    String title = I18n.instance.getString('alipsaAccounting.startup.verificationTitle')
    SwingUtilities.invokeLater {
      JOptionPane.showMessageDialog(null, message, title, JOptionPane.WARNING_MESSAGE)
    }
  }

  private static final class StartupOptions {

    final boolean verifyLaunchRequested
    final boolean versionRequested
    final String applicationHomeOverride

    private StartupOptions(boolean verifyLaunchRequested, boolean versionRequested, String applicationHomeOverride) {
      this.verifyLaunchRequested = verifyLaunchRequested
      this.versionRequested = versionRequested
      this.applicationHomeOverride = applicationHomeOverride
    }

    boolean getInteractive() {
      !verifyLaunchRequested && !versionRequested
    }

    static StartupOptions parse(String[] arguments) {
      boolean verifyLaunchRequested = false
      boolean versionRequested = false
      String applicationHomeOverride = null
      arguments.each { String argument ->
        if (argument == VERIFY_LAUNCH_ARGUMENT) {
          verifyLaunchRequested = true
          return
        }
        if (argument == VERSION_ARGUMENT) {
          versionRequested = true
          return
        }
        if (argument.startsWith(HOME_ARGUMENT_PREFIX)) {
          String value = argument.substring(HOME_ARGUMENT_PREFIX.length()).trim()
          if (!value) {
            throw new IllegalArgumentException(I18n.instance.getString('alipsaAccounting.error.emptyHome'))
          }
          applicationHomeOverride = value
          return
        }
        throw new IllegalArgumentException(I18n.instance.format('alipsaAccounting.error.unknownArgument', argument))
      }
      new StartupOptions(verifyLaunchRequested, versionRequested, applicationHomeOverride)
    }
  }
}
