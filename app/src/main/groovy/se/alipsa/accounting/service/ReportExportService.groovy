package se.alipsa.accounting.service

import groovy.transform.CompileStatic

import se.alipsa.accounting.domain.report.ReportArchive
import se.alipsa.accounting.domain.report.ReportResult
import se.alipsa.accounting.domain.report.ReportSelection

import java.nio.charset.StandardCharsets

/**
 * Exports tabular reports as CSV using the same selection and row order as the UI preview.
 * The export intentionally uses ';' as separator because Swedish Excel defaults expect it.
 */
@CompileStatic
final class ReportExportService {

  private final ReportDataService reportDataService
  private final ReportArchiveService reportArchiveService
  private final ReportIntegrityService reportIntegrityService
  private final AuditLogService auditLogService

  ReportExportService() {
    this(
        new ReportDataService(),
        new ReportArchiveService(),
        new ReportIntegrityService(),
        new AuditLogService()
    )
  }

  ReportExportService(
      ReportDataService reportDataService,
      ReportArchiveService reportArchiveService,
      ReportIntegrityService reportIntegrityService,
      AuditLogService auditLogService
  ) {
    this.reportDataService = reportDataService
    this.reportArchiveService = reportArchiveService
    this.reportIntegrityService = reportIntegrityService
    this.auditLogService = auditLogService
  }

  ReportResult preview(ReportSelection selection) {
    reportDataService.generate(selection)
  }

  byte[] renderCsv(ReportResult report) {
    if (!report.reportType.csvSupported) {
      throw new IllegalArgumentException("CSV-export stöds inte för ${report.reportType.label}.")
    }
    StringBuilder builder = new StringBuilder('\uFEFF')
    // Swedish locale users commonly open these files in Excel, which expects semicolon-separated UTF-8 with BOM.
    builder.append(report.tableHeaders.collect { String value -> escapeCsv(value) }.join(';')).append('\n')
    report.tableRows.each { List<String> row ->
      builder.append(row.collect { String value -> escapeCsv(value) }.join(';')).append('\n')
    }
    builder.toString().getBytes(StandardCharsets.UTF_8)
  }

  ReportArchive exportCsv(ReportSelection selection) {
    reportIntegrityService.ensureReportingAllowed()
    ReportResult report = preview(selection)
    byte[] csv = renderCsv(report)
    ReportArchive archive = reportArchiveService.archiveReport(
        new ReportSelection(report.reportType, report.fiscalYearId, report.accountingPeriodId, report.startDate, report.endDate),
        'CSV',
        csv
    )
    auditLogService.logExport(
        "CSV-rapport exporterad: ${report.reportType.label}",
        "archiveId=${archive.id}\nchecksumSha256=${archive.checksumSha256}\nstoragePath=${archive.storagePath}"
    )
    archive
  }

  private static String escapeCsv(String value) {
    String safeValue = value ?: ''
    if (startsFormula(safeValue)) {
      safeValue = "'${safeValue}"
    }
    if (!safeValue.contains(';') && !safeValue.contains('"') && !safeValue.contains('\n')) {
      return safeValue
    }
    "\"${safeValue.replace('"', '""')}\""
  }

  private static boolean startsFormula(String value) {
    if (!value) {
      return false
    }
    String first = value.substring(0, 1)
    if (first in ['=', '+', '@']) {
      return true
    }
    first == '-' && !(value ==~ /-\d+(\.\d+)?/)
  }
}
