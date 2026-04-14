package se.alipsa.accounting.service

import groovy.sql.GroovyRowResult
import groovy.sql.Sql

import se.alipsa.accounting.domain.Company
import se.alipsa.accounting.domain.report.ReportArchive
import se.alipsa.accounting.domain.report.ReportResult
import se.alipsa.accounting.domain.report.ReportSelection
import se.alipsa.journo.JournoEngine
import se.alipsa.journo.JournoException

/**
 * Renders PDF reports from Freemarker templates through Journo.
 */
final class JournoReportService {

  private final ReportDataService reportDataService
  private final ReportArchiveService reportArchiveService
  private final ReportIntegrityService reportIntegrityService
  private final CompanyService companyService
  private final AuditLogService auditLogService
  private final DatabaseService databaseService

  JournoReportService() {
    this(
        new ReportDataService(),
        new ReportArchiveService(),
        new ReportIntegrityService(),
        new CompanyService(),
        new AuditLogService(),
        DatabaseService.instance
    )
  }

  JournoReportService(
      ReportDataService reportDataService,
      ReportArchiveService reportArchiveService,
      ReportIntegrityService reportIntegrityService,
      CompanyService companyService,
      AuditLogService auditLogService,
      DatabaseService databaseService
  ) {
    this.reportDataService = reportDataService
    this.reportArchiveService = reportArchiveService
    this.reportIntegrityService = reportIntegrityService
    this.companyService = companyService
    this.auditLogService = auditLogService
    this.databaseService = databaseService
  }

  ReportResult preview(ReportSelection selection) {
    reportDataService.generate(selection)
  }

  String renderHtml(ReportResult report) {
    try {
      createJournoEngine().renderHtml(report.reportType.templateName, buildTemplateModel(report))
    } catch (JournoException exception) {
      throw new IllegalStateException("PDF-förhandsvisningen kunde inte renderas för ${report.reportType.displayName}.", exception)
    }
  }

  byte[] renderPdf(ReportResult report) {
    try {
      createJournoEngine().renderPdf(report.reportType.templateName, buildTemplateModel(report))
    } catch (JournoException exception) {
      throw new IllegalStateException("PDF kunde inte skapas för ${report.reportType.displayName}.", exception)
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
    long companyId = resolveCompanyId(report.fiscalYearId)
    auditLogService.logExport(
        "PDF-rapport skapad: ${report.reportType.displayName}",
        "archiveId=${archive.id}\nchecksumSha256=${archive.checksumSha256}\nstoragePath=${archive.storagePath}",
        companyId
    )
    archive
  }

  private Map<String, Object> buildTemplateModel(ReportResult report) {
    long companyId = resolveCompanyId(report.fiscalYearId)
    Company company = companyService.findById(companyId)
    [
        companyName       : company?.companyName ?: 'Alipsa Accounting',
        organizationNumber: company?.organizationNumber ?: '',
        report            : report
    ] + report.templateModel
  }

  private long resolveCompanyId(long fiscalYearId) {
    databaseService.withSql { Sql sql ->
      GroovyRowResult row = sql.firstRow(
          'select company_id as companyId from fiscal_year where id = ?',
          [fiscalYearId]
      ) as GroovyRowResult
      if (row == null) {
        throw new IllegalArgumentException("Okänt räkenskapsår: ${fiscalYearId}")
      }
      ((Number) row.get('companyId')).longValue()
    }
  }

  private static JournoEngine createJournoEngine() {
    new JournoEngine(JournoReportService, '/reports')
  }
}
