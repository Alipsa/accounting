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
import se.alipsa.accounting.support.LoggingConfigurer

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.awt.Image
import java.util.logging.Logger

import javax.imageio.ImageIO
import javax.swing.*

/**
 * Main desktop window with phase two navigation and setup actions.
 */
@CompileDynamic
final class MainFrame {

  private static final Logger log = Logger.getLogger(MainFrame.name)
  private static final List<Map<String, String>> PLACEHOLDER_TABS = [
      [title: 'Översikt', description: 'Översikt och status för bokföringen kommer här.'],
      [title: 'Rapporter', description: 'Rapporter och export kommer här.']
  ]
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
  private final JFrame frame

  MainFrame() {
    frame = buildFrame()
    applyIcons()
    refreshCompanySettingsSummary()
    setStatus('Applikationen är startad och redo för kontoplan, företagsinställningar och räkenskapsår.')
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

  private JFrame buildFrame() {
    swing.frame(
        title: 'Alipsa Accounting',
        size: [1100, 720],
        defaultCloseOperation: JFrame.EXIT_ON_CLOSE,
        locationByPlatform: true,
        show: false
    ) {
      lookAndFeel 'system'
      menuBar {
        menu(text: 'Arkiv') {
          menuItem(text: 'Företagsuppgifter...', actionPerformed: { showCompanySettingsDialog() })
          menuItem(text: 'SIE import/export...', actionPerformed: { showSieExchangeDialog() })
          menuItem(text: 'Avsluta', actionPerformed: { exitRequested() })
        }
        menu(text: 'Hjälp') {
          menuItem(text: 'Användarmanual', actionPerformed: { showUserManualDialog() })
          menuItem(text: 'Om', actionPerformed: { showAboutDialog() })
        }
      }
      borderLayout()
      panel(constraints: BorderLayout.NORTH, border: swing.emptyBorder(12, 16, 8, 16)) {
        borderLayout()
        label(
            text: 'Alipsa Accounting',
            horizontalAlignment: SwingConstants.LEFT,
            font: new Font('Dialog', Font.BOLD, 22),
            constraints: BorderLayout.CENTER
        )
      }
      tabbedPane(constraints: BorderLayout.CENTER) {
        buildMainTabs().each { Map<String, Object> tab ->
          widget(tab.component, title: tab.title as String)
        }
      }
      panel(constraints: BorderLayout.SOUTH, border: swing.lineBorder(color: Color.LIGHT_GRAY)) {
        borderLayout()
        statusLabel = label(
            text: 'Redo',
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
        button(text: 'Redigera företagsuppgifter', actionPerformed: { showCompanySettingsDialog() })
      }
      companySummaryLabel = label(
          text: '',
          verticalAlignment: SwingConstants.TOP,
          constraints: BorderLayout.CENTER
      ) as JLabel
    } as JPanel
  }

  private JPanel buildPlaceholderPanel(String title, String description) {
    String safeTitle = escapeHtml(title)
    String safeDescription = escapeHtml(description)
    swing.panel(border: swing.emptyBorder(24, 24, 24, 24)) {
      borderLayout()
      label(
          text: "<html><h2>${safeTitle}</h2><p>${safeDescription}</p></html>",
          horizontalAlignment: SwingConstants.CENTER,
          constraints: BorderLayout.CENTER
      )
    } as JPanel
  }

  private List<Map<String, Object>> buildMainTabs() {
    [
        [title: PLACEHOLDER_TABS[0].title, component: buildPlaceholderPanel(PLACEHOLDER_TABS[0].title, PLACEHOLDER_TABS[0].description)],
        [title: 'Verifikationer', component: new VoucherListPanel(voucherService, fiscalYearService, accountService, attachmentService, auditLogService)],
        [title: 'Moms', component: new VatPeriodPanel(vatService, fiscalYearService)],
        [title: 'Rapporter', component: new ReportPanel(
            reportDataService,
            journoReportService,
            reportExportService,
            reportArchiveService,
            fiscalYearService,
            accountingPeriodService,
            new VoucherEditor.Dependencies(voucherService, fiscalYearService, accountService, attachmentService, auditLogService)
        )],
        [title: 'Kontoplan', component: new ChartOfAccountsPanel(accountService, chartOfAccountsImportService, fiscalYearService)],
        [title: 'Räkenskapsår', component: new FiscalYearPanel(fiscalYearService, accountingPeriodService, closingService)],
        [title: 'System', component: new SystemDocumentationPanel(
            systemDocumentationService,
            diagnosticsService,
            backupService,
            userManualService
        )],
        [title: 'Inställningar', component: buildCompanySettingsPanel()]
    ]
  }

  private void exitRequested() {
    LoggingConfigurer.shutdown()
    frame.dispose()
  }

  private void showAboutDialog() {
    ImageIcon icon = loadIcon('/icons/logo64.png')
    JOptionPane.showMessageDialog(
        frame,
        'Alipsa Accounting\nFas 10: drift, backup, återställning och systemdokumentation.',
        'Om Alipsa Accounting',
        JOptionPane.INFORMATION_MESSAGE,
        icon
    )
  }

  private void showCompanySettingsDialog() {
    CompanySettingsDialog.showDialog(frame, companySettingsService, {
      refreshCompanySettingsSummary()
      setStatus('Företagsinställningarna sparades.')
    } as Runnable)
  }

  private void showSieExchangeDialog() {
    SieExchangeDialog.showDialog(frame, sieImportExportService, fiscalYearService)
    setStatus('SIE-import/export stängdes.')
  }

  private void showUserManualDialog() {
    UserManualDialog.showDialog(frame, userManualService)
    setStatus('Användarmanualen visades.')
  }

  private void refreshCompanySettingsSummary() {
    if (companySummaryLabel == null) {
      return
    }
    CompanySettings settings = companySettingsService.getSettings()
    if (settings == null) {
      companySummaryLabel.text = '''
                <html>
                <h2>Företagsinställningar</h2>
                <p>Ingen företagsprofil är sparad än. Öppna dialogen för att genomföra grundinställningen.</p>
                </html>
            '''.stripIndent().trim()
      return
    }
    companySummaryLabel.text = """
            <html>
            <h2>${escapeHtml(settings.companyName)}</h2>
            <p>Organisationsnummer: ${escapeHtml(settings.organizationNumber)}</p>
            <p>Valuta: ${escapeHtml(settings.defaultCurrency)}</p>
            <p>Locale: ${escapeHtml(settings.localeTag)}</p>
            <p>Momsperiod: ${escapeHtml(settings.vatPeriodicity?.label ?: 'Månadsvis')}</p>
            </html>
        """.stripIndent().trim()
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
