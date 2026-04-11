package se.alipsa.accounting.service

import groovy.transform.CompileStatic

import se.alipsa.accounting.domain.CompanySettings
import se.alipsa.accounting.domain.report.ReportArchive
import se.alipsa.accounting.domain.report.ReportResult
import se.alipsa.accounting.domain.report.ReportSelection
import se.alipsa.journo.JournoEngine
import se.alipsa.journo.JournoException

/**
 * Renders PDF reports from Freemarker templates through Journo.
 */
@CompileStatic
final class JournoReportService {

  private final ReportDataService reportDataService
  private final ReportArchiveService reportArchiveService
  private final ReportIntegrityService reportIntegrityService
  private final CompanySettingsService companySettingsService
  private final AuditLogService auditLogService
  private final JournoEngine journoEngine = new JournoEngine(JournoReportService, '/reports')

  JournoReportService() {
    this(
        new ReportDataService(),
        new ReportArchiveService(),
        new ReportIntegrityService(),
        new CompanySettingsService(),
        new AuditLogService()
    )
  }

  JournoReportService(
      ReportDataService reportDataService,
      ReportArchiveService reportArchiveService,
      ReportIntegrityService reportIntegrityService,
      CompanySettingsService companySettingsService,
      AuditLogService auditLogService
  ) {
    this.reportDataService = reportDataService
    this.reportArchiveService = reportArchiveService
    this.reportIntegrityService = reportIntegrityService
    this.companySettingsService = companySettingsService
    this.auditLogService = auditLogService
  }

  ReportResult preview(ReportSelection selection) {
    reportDataService.generate(selection)
  }

  String renderHtml(ReportResult report) {
    try {
      journoEngine.renderHtml(report.reportType.templateName, buildTemplateModel(report))
    } catch (JournoException exception) {
      throw new IllegalStateException("PDF-förhandsvisningen kunde inte renderas för ${report.reportType.label}.", exception)
    }
  }

  byte[] renderPdf(ReportResult report) {
    try {
      journoEngine.renderPdf(report.reportType.templateName, buildTemplateModel(report))
    } catch (JournoException exception) {
      throw new IllegalStateException("PDF kunde inte skapas för ${report.reportType.label}.", exception)
    }
  }

  ReportArchive generatePdf(ReportSelection selection) {
    reportIntegrityService.ensureReportingAllowed()
    ReportResult report = preview(selection)
    byte[] pdf = renderPdf(report)
    ReportArchive archive = reportArchiveService.archiveReport(
        new ReportSelection(report.reportType, report.fiscalYearId, report.accountingPeriodId, report.startDate, report.endDate),
        'PDF',
        pdf
    )
    auditLogService.logExport(
        "PDF-rapport skapad: ${report.reportType.label}",
        "archiveId=${archive.id}\nchecksumSha256=${archive.checksumSha256}\nstoragePath=${archive.storagePath}"
    )
    archive
  }

  private Map<String, Object> buildTemplateModel(ReportResult report) {
    CompanySettings settings = companySettingsService.getSettings()
    [
        companyName       : settings?.companyName ?: 'Alipsa Accounting',
        organizationNumber: settings?.organizationNumber ?: '',
        report            : report
    ] + report.templateModel
  }
}
