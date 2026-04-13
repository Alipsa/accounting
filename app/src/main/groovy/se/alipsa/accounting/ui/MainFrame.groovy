package se.alipsa.accounting.ui

import groovy.swing.SwingBuilder
import groovy.transform.CompileDynamic

import se.alipsa.accounting.domain.CompanySettings
import se.alipsa.accounting.service.AccountService
import se.alipsa.accounting.service.AccountingPeriodService
import se.alipsa.accounting.service.AttachmentService
import se.alipsa.accounting.service.AuditLogService
import se.alipsa.accounting.service.BackupService
import se.alipsa.accounting.service.ChartOfAccountsImportService
import se.alipsa.accounting.service.ClosingService
import se.alipsa.accounting.service.CompanySettingsService
import se.alipsa.accounting.service.DatabaseService
import se.alipsa.accounting.service.FiscalYearService
import se.alipsa.accounting.service.JournoReportService
import se.alipsa.accounting.service.MigrationService
import se.alipsa.accounting.service.ReportArchiveService
import se.alipsa.accounting.service.ReportDataService
import se.alipsa.accounting.service.ReportExportService
import se.alipsa.accounting.service.ReportIntegrityService
import se.alipsa.accounting.service.SieImportExportService
import se.alipsa.accounting.service.StartupVerificationService
import se.alipsa.accounting.service.SystemDiagnosticsService
import se.alipsa.accounting.service.SystemDocumentationService
import se.alipsa.accounting.service.UserManualService
import se.alipsa.accounting.service.VatService
import se.alipsa.accounting.service.VoucherService
import se.alipsa.accounting.support.I18n
import se.alipsa.accounting.support.LoggingConfigurer

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.awt.Image
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import java.util.logging.Logger

import javax.imageio.ImageIO
import javax.swing.*

/**
 * Main desktop window with phase two navigation and setup actions.
 */
@CompileDynamic
final class MainFrame implements PropertyChangeListener {

  private static final Logger log = Logger.getLogger(MainFrame.name)
  private static final List<String> ICON_PATHS = [
      '/icons/logo16.png',
      '/icons/logo32.png',
      '/icons/logo64.png',
      '/icons/logo128.png'
  ]

  private final SwingBuilder swing = new SwingBuilder()
  private final CompanySettingsService companySettingsService = new CompanySettingsService()
  private final AuditLogService auditLogService = new AuditLogService()
  private final AccountingPeriodService accountingPeriodService = new AccountingPeriodService()
  private final AccountService accountService = new AccountService()
  private final AttachmentService attachmentService = new AttachmentService(DatabaseService.instance, auditLogService)
  private final ChartOfAccountsImportService chartOfAccountsImportService = new ChartOfAccountsImportService()
  private final FiscalYearService fiscalYearService = new FiscalYearService(DatabaseService.instance, accountingPeriodService, auditLogService)
  private final VoucherService voucherService = new VoucherService(DatabaseService.instance, auditLogService)
  private final VatService vatService = new VatService(DatabaseService.instance, voucherService)
  private final MigrationService migrationService = new MigrationService(DatabaseService.instance)
  private final ReportDataService reportDataService = new ReportDataService(DatabaseService.instance, fiscalYearService, accountingPeriodService)
  private final ReportArchiveService reportArchiveService = new ReportArchiveService(DatabaseService.instance)
  private final ReportIntegrityService reportIntegrityService = new ReportIntegrityService(voucherService, attachmentService, auditLogService)
  private final BackupService backupService = new BackupService(
      DatabaseService.instance,
      attachmentService,
      reportArchiveService,
      auditLogService,
      migrationService,
      reportIntegrityService
  )
  private final ClosingService closingService = new ClosingService(
      DatabaseService.instance,
      accountingPeriodService,
      fiscalYearService,
      voucherService,
      reportIntegrityService
  )
  private final StartupVerificationService startupVerificationService = new StartupVerificationService(
      DatabaseService.instance,
      reportIntegrityService,
      reportArchiveService
  )
  private final SystemDiagnosticsService diagnosticsService = new SystemDiagnosticsService(
      migrationService,
      startupVerificationService,
      backupService,
      auditLogService
  )
  private final UserManualService userManualService = new UserManualService()
  private final SystemDocumentationService systemDocumentationService = new SystemDocumentationService(
      migrationService,
      diagnosticsService,
      attachmentService,
      reportArchiveService,
      auditLogService
  )
  private final ReportExportService reportExportService = new ReportExportService(
      reportDataService,
      reportArchiveService,
      reportIntegrityService,
      auditLogService
  )
  private final SieImportExportService sieImportExportService = new SieImportExportService(
      DatabaseService.instance,
      accountingPeriodService,
      voucherService,
      companySettingsService,
      reportIntegrityService,
      auditLogService
  )
  private final JournoReportService journoReportService = new JournoReportService(
      reportDataService,
      reportArchiveService,
      reportIntegrityService,
      companySettingsService,
      auditLogService
  )
  private JLabel statusLabel
  private JLabel companySummaryLabel
  private JLabel headerLabel
  private JLabel overviewDescriptionLabel
  private JMenu fileMenu
  private JMenuItem companySettingsMenuItem
  private JMenuItem sieExchangeMenuItem
  private JMenuItem exitMenuItem
  private JMenu helpMenu
  private JMenuItem manualMenuItem
  private JMenuItem updateMenuItem
  private JMenuItem aboutMenuItem
  private JButton editCompanySettingsButton
  private JTabbedPane tabbedPane
  private final JFrame frame

  MainFrame() {
    I18n.instance.addLocaleChangeListener(this)
    frame = buildFrame()
    applyIcons()
    refreshCompanySettingsSummary()
    setStatus(I18n.instance.getString('mainFrame.status.started'))
  }

  void display() {
    frame.visible = true
    if (!companySettingsService.isConfigured()) {
      SwingUtilities.invokeLater {
        showCompanySettingsDialog()
      }
    }
  }

  void setStatus(String text) {
    statusLabel.text = text
  }

  @Override
  void propertyChange(PropertyChangeEvent evt) {
    if ('locale' == evt.propertyName) {
      SwingUtilities.invokeLater { applyLocale() }
    }
  }

  private void applyLocale() {
    frame.title = I18n.instance.getString('mainFrame.title')
    headerLabel.text = I18n.instance.getString('mainFrame.title')
    statusLabel.text = I18n.instance.getString('mainFrame.status.ready')

    fileMenu.text = I18n.instance.getString('mainFrame.menu.file')
    companySettingsMenuItem.text = I18n.instance.getString('mainFrame.menu.file.companySettings')
    sieExchangeMenuItem.text = I18n.instance.getString('mainFrame.menu.file.sieExchange')
    exitMenuItem.text = I18n.instance.getString('mainFrame.menu.file.exit')

    helpMenu.text = I18n.instance.getString('mainFrame.menu.help')
    manualMenuItem.text = I18n.instance.getString('mainFrame.menu.help.manual')
    updateMenuItem.text = I18n.instance.getString('mainFrame.menu.help.checkForUpdates')
    aboutMenuItem.text = I18n.instance.getString('mainFrame.menu.help.about')

    tabbedPane.setTitleAt(0, I18n.instance.getString('mainFrame.tab.overview'))
    tabbedPane.setTitleAt(1, I18n.instance.getString('mainFrame.tab.vouchers'))
    tabbedPane.setTitleAt(2, I18n.instance.getString('mainFrame.tab.vat'))
    tabbedPane.setTitleAt(3, I18n.instance.getString('mainFrame.tab.reports'))
    tabbedPane.setTitleAt(4, I18n.instance.getString('mainFrame.tab.chartOfAccounts'))
    tabbedPane.setTitleAt(5, I18n.instance.getString('mainFrame.tab.fiscalYears'))
    tabbedPane.setTitleAt(6, I18n.instance.getString('mainFrame.tab.system'))
    tabbedPane.setTitleAt(7, I18n.instance.getString('mainFrame.tab.settings'))

    editCompanySettingsButton.text = I18n.instance.getString('mainFrame.button.editCompanySettings')

    String overviewTitle = escapeHtml(I18n.instance.getString('mainFrame.tab.overview'))
    String overviewDesc = escapeHtml(I18n.instance.getString('mainFrame.tab.overview.description'))
    overviewDescriptionLabel.text = "<html><h2>${overviewTitle}</h2><p>${overviewDesc}</p></html>"

    refreshCompanySettingsSummary()
  }

  private JFrame buildFrame() {
    swing.frame(
        title: I18n.instance.getString('mainFrame.title'),
        size: [1100, 720],
        defaultCloseOperation: JFrame.EXIT_ON_CLOSE,
        locationByPlatform: true,
        show: false
    ) {
      lookAndFeel 'system'
      menuBar {
        fileMenu = menu(text: I18n.instance.getString('mainFrame.menu.file')) {
          companySettingsMenuItem = menuItem(text: I18n.instance.getString('mainFrame.menu.file.companySettings'), actionPerformed: { showCompanySettingsDialog() })
          sieExchangeMenuItem = menuItem(text: I18n.instance.getString('mainFrame.menu.file.sieExchange'), actionPerformed: { showSieExchangeDialog() })
          exitMenuItem = menuItem(text: I18n.instance.getString('mainFrame.menu.file.exit'), actionPerformed: { exitRequested() })
        }
        helpMenu = menu(text: I18n.instance.getString('mainFrame.menu.help')) {
          manualMenuItem = menuItem(text: I18n.instance.getString('mainFrame.menu.help.manual'), actionPerformed: { showUserManualDialog() })
          updateMenuItem = menuItem(text: I18n.instance.getString('mainFrame.menu.help.checkForUpdates'), actionPerformed: { showUpdateDialog() })
          aboutMenuItem = menuItem(text: I18n.instance.getString('mainFrame.menu.help.about'), actionPerformed: { showAboutDialog() })
        }
      }
      borderLayout()
      panel(constraints: BorderLayout.NORTH, border: swing.emptyBorder(12, 16, 8, 16)) {
        borderLayout()
        headerLabel = label(
            text: I18n.instance.getString('mainFrame.title'),
            horizontalAlignment: SwingConstants.LEFT,
            font: new Font('Dialog', Font.BOLD, 22),
            constraints: BorderLayout.CENTER
        )
      }
      tabbedPane = tabbedPane(constraints: BorderLayout.CENTER) {
        buildMainTabs().each { Map<String, Object> tab ->
          widget(tab.component, title: tab.title as String)
        }
      }
      panel(constraints: BorderLayout.SOUTH, border: swing.lineBorder(color: Color.LIGHT_GRAY)) {
        borderLayout()
        statusLabel = label(
            text: I18n.instance.getString('mainFrame.status.ready'),
            border: swing.emptyBorder(6, 12, 6, 12),
            constraints: BorderLayout.CENTER
        ) as JLabel
      }
    } as JFrame
  }

  private JPanel buildCompanySettingsPanel() {
    swing.panel(border: swing.emptyBorder(24, 24, 24, 24)) {
      borderLayout(vgap: 12)
      panel(constraints: BorderLayout.NORTH) {
        flowLayout(alignment: java.awt.FlowLayout.LEFT, hgap: 8, vgap: 0)
        editCompanySettingsButton = button(text: I18n.instance.getString('mainFrame.button.editCompanySettings'), actionPerformed: { showCompanySettingsDialog() })
      }
      companySummaryLabel = label(
          text: '',
          verticalAlignment: SwingConstants.TOP,
          constraints: BorderLayout.CENTER
      ) as JLabel
    } as JPanel
  }

  private JPanel buildOverviewPanel() {
    String safeTitle = escapeHtml(I18n.instance.getString('mainFrame.tab.overview'))
    String safeDescription = escapeHtml(I18n.instance.getString('mainFrame.tab.overview.description'))
    swing.panel(border: swing.emptyBorder(24, 24, 24, 24)) {
      borderLayout()
      overviewDescriptionLabel = label(
          text: "<html><h2>${safeTitle}</h2><p>${safeDescription}</p></html>",
          horizontalAlignment: SwingConstants.CENTER,
          constraints: BorderLayout.CENTER
      )
    } as JPanel
  }

  private List<Map<String, Object>> buildMainTabs() {
    [
        [title: I18n.instance.getString('mainFrame.tab.overview'), component: buildOverviewPanel()],
        [title: I18n.instance.getString('mainFrame.tab.vouchers'), component: new VoucherListPanel(voucherService, fiscalYearService, accountService, attachmentService, auditLogService)],
        [title: I18n.instance.getString('mainFrame.tab.vat'), component: new VatPeriodPanel(vatService, fiscalYearService)],
        [title: I18n.instance.getString('mainFrame.tab.reports'), component: new ReportPanel(
            reportDataService,
            journoReportService,
            reportExportService,
            reportArchiveService,
            fiscalYearService,
            accountingPeriodService,
            new VoucherEditor.Dependencies(voucherService, fiscalYearService, accountService, attachmentService, auditLogService)
        )],
        [title: I18n.instance.getString('mainFrame.tab.chartOfAccounts'), component: new ChartOfAccountsPanel(accountService, chartOfAccountsImportService, fiscalYearService)],
        [title: I18n.instance.getString('mainFrame.tab.fiscalYears'), component: new FiscalYearPanel(fiscalYearService, accountingPeriodService, closingService)],
        [title: I18n.instance.getString('mainFrame.tab.system'), component: new SystemDocumentationPanel(
            systemDocumentationService,
            diagnosticsService,
            backupService,
            userManualService
        )],
        [title: I18n.instance.getString('mainFrame.tab.settings'), component: buildCompanySettingsPanel()]
    ]
  }

  private void exitRequested() {
    LoggingConfigurer.shutdown()
    frame.dispose()
  }

  private void showAboutDialog() {
    ImageIcon icon = loadIcon('/icons/logo64.png')
    String version = MainFrame.package?.implementationVersion ?: I18n.instance.getString('mainFrame.about.versionDefault')
    JOptionPane.showMessageDialog(
        frame,
        I18n.instance.format('mainFrame.about.message', version),
        I18n.instance.getString('mainFrame.about.title'),
        JOptionPane.INFORMATION_MESSAGE,
        icon
    )
  }

  private void showCompanySettingsDialog() {
    CompanySettingsDialog.showDialog(frame, companySettingsService, {
      refreshCompanySettingsSummary()
      setStatus(I18n.instance.getString('mainFrame.status.companySaved'))
    } as Runnable)
  }

  private void showSieExchangeDialog() {
    SieExchangeDialog.showDialog(frame, sieImportExportService, fiscalYearService)
    setStatus(I18n.instance.getString('mainFrame.status.sieExchangeClosed'))
  }

  private void showUserManualDialog() {
    UserManualDialog.showDialog(frame, userManualService)
    setStatus(I18n.instance.getString('mainFrame.status.manualShown'))
  }

  private void showUpdateDialog() {
    UpdateDialog.showDialog(frame)
  }

  private void refreshCompanySettingsSummary() {
    if (companySummaryLabel == null) {
      return
    }
    CompanySettings settings = companySettingsService.getSettings()
    if (settings == null) {
      companySummaryLabel.text = I18n.instance.getString('mainFrame.companySettings.noProfile')
      return
    }
    companySummaryLabel.text = I18n.instance.format(
        'mainFrame.companySettings.summary',
        escapeHtml(settings.companyName),
        I18n.instance.getString('mainFrame.companySettings.orgNumber'), escapeHtml(settings.organizationNumber),
        I18n.instance.getString('mainFrame.companySettings.currency'), escapeHtml(settings.defaultCurrency),
        escapeHtml(settings.localeTag),
        I18n.instance.getString('mainFrame.companySettings.vatPeriod'), escapeHtml(settings.vatPeriodicity?.displayName ?: I18n.instance.getString('vatPeriodicity.MONTHLY'))
    )
  }

  private void applyIcons() {
    List<Image> icons = []
    ICON_PATHS.each { String path ->
      ImageIcon icon = loadIcon(path)
      if (icon != null) {
        icons << icon.image
      }
    }
    if (icons) {
      frame.iconImages = icons
    } else {
      log.fine('No application icons were available on the classpath.')
    }
  }

  private ImageIcon loadIcon(String path) {
    InputStream stream = MainFrame.getResourceAsStream(path)
    if (stream == null) {
      return null
    }
    stream.withCloseable { InputStream input ->
      new ImageIcon(ImageIO.read(input))
    }
  }

  private static String escapeHtml(String text) {
    text
        .replace('&', '&amp;')
        .replace('<', '&lt;')
        .replace('>', '&gt;')
  }
}
