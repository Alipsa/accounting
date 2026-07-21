package se.alipsa.accounting

import se.alipsa.accounting.service.DatabaseService
import se.alipsa.accounting.service.StartupVerificationReport
import se.alipsa.accounting.service.StartupVerificationService
import se.alipsa.accounting.service.UserPreferencesService
import se.alipsa.accounting.support.AppPaths
import se.alipsa.accounting.support.DataLocationResolver
import se.alipsa.accounting.support.I18n
import se.alipsa.accounting.support.LoggingConfigurer
import se.alipsa.accounting.ui.DataLocationDialog
import se.alipsa.accounting.ui.MainFrame
import se.alipsa.accounting.ui.StartupSplash
import se.alipsa.accounting.ui.ThemeApplier

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
  private static final String MODE_ARGUMENT_PREFIX = '--mode='
  private static final String APP_WINDOW_CLASS = 'se-alipsa-accounting-AlipsaAccounting'
  private static final String PROCESS_LAUNCH_MECHANISM_PROPERTY = 'jdk.lang.Process.launchMechanism'
  private static final String LINUX_WINDOW_CLASS_PROPERTY = 'sun.awt.X11.XWMClass'

  private AlipsaAccounting() {
  }

  static void main(String[] args) {
    configureProcessLaunchMechanism()
    configureLinuxWindowClass()
    StartupOptions options = StartupOptions.parse(args ?: new String[0])
    if (options.versionRequested) {
      System.out.println(versionLine())
      return
    }
    UserPreferencesService userPreferencesService = new UserPreferencesService()
    applyStartupLocale(userPreferencesService)
    resolveApplicationHome(options, userPreferencesService)
    StartupSplash splash = StartupSplash.showIfPossible(options.interactive)
    try {
      runApplication(options, splash, userPreferencesService)
    } catch (Exception exception) {
      splash.close()
      log.log(Level.SEVERE, 'Failed to start Alipsa Accounting.', exception)
      if (options.interactive) {
        showStartupError(exception)
      } else {
        System.err.println("Failed to start Alipsa Accounting: ${exception.message ?: exception.class.simpleName}")
      }
      throw exception
    }
  }

  /**
   * Applies the saved UI language (falling back to the JVM default) before anything that might
   * show a dialog - including data-location resolution - so those dialogs aren't stuck on the
   * hardcoded English default. {@code UserPreferencesService} has no database dependency, so
   * this is safe to read before {@code DatabaseService} is initialized.
   */
  private static void applyStartupLocale(UserPreferencesService userPreferencesService) {
    Locale savedLanguage = userPreferencesService.getLanguage()
    I18n.instance.setLocale(savedLanguage ?: Locale.getDefault())
  }

  private static void resolveApplicationHome(StartupOptions options, UserPreferencesService userPreferencesService) {
    if (options.applicationHomeOverride != null) {
      System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, options.applicationHomeOverride)
    } else {
      resolveDataLocation(options.interactive, userPreferencesService)
    }
  }

  private static void runApplication(StartupOptions options, StartupSplash splash, UserPreferencesService userPreferencesService) {
    LoggingConfigurer.configure()
    DatabaseService.instance.initialize()
    ThemeApplier.apply(userPreferencesService.getTheme())
    StartupVerificationReport startupReport = new StartupVerificationService().verify()
    if (options.verifyLaunchRequested) {
      splash.close()
      handleVerifyLaunch(startupReport)
      return
    }
    if (!startupReport.ok || !startupReport.warnings.isEmpty()) {
      splash.close()
      showStartupVerificationWarning(startupReport)
    }
    SwingUtilities.invokeLater {
      try {
        MainFrame mainFrame = new MainFrame()
        mainFrame.display()
      } finally {
        splash.close()
      }
    }
  }

  private static void handleVerifyLaunch(StartupVerificationReport startupReport) {
    failOnStartupErrors(startupReport)
    String version = AlipsaAccounting.package?.implementationVersion
    if (!version) {
      log.warning('JAR manifest saknar Implementation-Version — paketeringen kan vara felaktig.')
    }
    if (!startupReport.warnings.isEmpty()) {
      log.warning("Launch verification completed with warnings: ${startupReport.warnings.join(' | ')}")
    }
    System.out.println("Launch verification OK: ${versionLine()} [home=${AppPaths.applicationHome()}]")
    // Release the H2 and logging file handles so the caller (tests, packaging verification)
    // can delete the verification home directory on Windows.
    DatabaseService.instance.shutdown()
    LoggingConfigurer.shutdown()
  }

  /**
   * Resolves the configured data location (if any) before {@code DatabaseService.instance}
   * is initialized, applying any pending migration and setting the home override system
   * property. Only skipped when {@code --home=} was passed explicitly, which always wins.
   */
  private static void resolveDataLocation(boolean interactive, UserPreferencesService preferences) {
    while (true) {
      DataLocationResolver.Outcome outcome = DataLocationResolver.resolve(preferences)
      if (outcome.migrationFailed) {
        reportMigrationFailure(outcome.migrationNote, interactive)
      } else if (outcome.migrationNote) {
        log.info(outcome.migrationNote)
      }
      if (outcome.reachable) {
        if (outcome.location) {
          System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, outcome.location)
        }
        return
      }
      if (!interactive || GraphicsEnvironment.headless) {
        throw new IllegalStateException(
            "Configured data location is not accessible: ${outcome.location} (${outcome.reachabilityError})".toString()
        )
      }
      if (!offerToResolveUnreachableDataLocation(outcome, preferences)) {
        System.exit(0)
        return
      }
    }
  }

  /**
   * Surfaces a failed pending migration to the user instead of letting it pass silently as a
   * log line - the whole point of the feature is a two-machine shared location, so a failed
   * move needs to be visible, not just logged.
   */
  private static void reportMigrationFailure(String message, boolean interactive) {
    log.warning("Data location migration failed: ${message}".toString())
    if (!interactive || GraphicsEnvironment.headless) {
      return
    }
    runOnEventDispatchThread {
      JOptionPane.showMessageDialog(
          null,
          I18n.instance.format('alipsaAccounting.dataLocation.migrationFailedMessage', message),
          I18n.instance.getString('alipsaAccounting.dataLocation.migrationFailedTitle'),
          JOptionPane.WARNING_MESSAGE
      )
    }
  }

  /**
   * Shows a blocking Retry / Change location / Quit dialog for an unreachable configured
   * location. Returns {@code true} if the caller should retry resolution, {@code false}
   * if the user chose to quit.
   */
  private static boolean offerToResolveUnreachableDataLocation(
      DataLocationResolver.Outcome outcome, UserPreferencesService preferences) {
    String message = I18n.instance.format(
        'alipsaAccounting.dataLocation.unreachableMessage', outcome.location, outcome.reachabilityError)
    String title = I18n.instance.getString('alipsaAccounting.dataLocation.unreachableTitle')
    String retry = I18n.instance.getString('alipsaAccounting.dataLocation.retry')
    String change = I18n.instance.getString('alipsaAccounting.dataLocation.change')
    String quit = I18n.instance.getString('alipsaAccounting.dataLocation.quit')
    Object[] choices = [retry, change, quit] as Object[]

    int[] selection = new int[1]
    runOnEventDispatchThread {
      selection[0] = JOptionPane.showOptionDialog(
          null, message, title, JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, choices, retry)
    }
    if (selection[0] == 1) {
      runOnEventDispatchThread {
        DataLocationDialog.showDialog(null, preferences)
      }
      return true
    }
    selection[0] == 0
  }

  private static void runOnEventDispatchThread(Runnable action) {
    if (SwingUtilities.isEventDispatchThread()) {
      action.run()
      return
    }
    SwingUtilities.invokeAndWait(action)
  }

  private static void configureProcessLaunchMechanism() {
    if (System.getProperty(PROCESS_LAUNCH_MECHANISM_PROPERTY) != null) {
      return
    }
    String osName = System.getProperty('os.name', '').toLowerCase(Locale.ROOT)
    if (osName.contains('linux')) {
      System.setProperty(PROCESS_LAUNCH_MECHANISM_PROPERTY, 'VFORK')
    }
  }

  private static void configureLinuxWindowClass() {
    if (System.getProperty(LINUX_WINDOW_CLASS_PROPERTY) != null) {
      return
    }
    String osName = System.getProperty('os.name', '').toLowerCase(Locale.ROOT)
    if (osName.contains('linux')) {
      System.setProperty(LINUX_WINDOW_CLASS_PROPERTY, APP_WINDOW_CLASS)
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

    private StartupOptions(
        boolean verifyLaunchRequested,
        boolean versionRequested,
        String applicationHomeOverride
    ) {
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
        if (argument.startsWith(MODE_ARGUMENT_PREFIX)) {
          throw new IllegalArgumentException(I18n.instance.format('alipsaAccounting.error.unknownArgument', argument))
        }
        throw new IllegalArgumentException(I18n.instance.format('alipsaAccounting.error.unknownArgument', argument))
      }
      new StartupOptions(verifyLaunchRequested, versionRequested, applicationHomeOverride)
    }
  }
}
