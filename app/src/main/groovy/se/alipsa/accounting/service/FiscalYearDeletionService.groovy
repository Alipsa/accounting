package se.alipsa.accounting.service

import groovy.sql.Sql

import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.support.AppPaths

import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger

/**
 * Permanent deletion of a fiscal year after the seven-year retention period.
 */
final class FiscalYearDeletionService {

  private static final Logger log = Logger.getLogger(FiscalYearDeletionService.name)

  private final DatabaseService databaseService
  private final RetentionPolicyService retentionPolicyService
  private final AuditLogService auditLogService
  private final FiscalYearService fiscalYearService

  FiscalYearDeletionService(DatabaseService databaseService) {
    this(databaseService, new RetentionPolicyService(), new AuditLogService(databaseService),
        new FiscalYearService(databaseService))
  }

  FiscalYearDeletionService(
      DatabaseService databaseService,
      RetentionPolicyService retentionPolicyService,
      AuditLogService auditLogService,
      FiscalYearService fiscalYearService
  ) {
    this.databaseService = databaseService
    this.retentionPolicyService = retentionPolicyService
    this.auditLogService = auditLogService
    this.fiscalYearService = fiscalYearService
  }

  FiscalYearReplacementPlan previewDeletion(long fiscalYearId) {
    databaseService.withSql { Sql sql ->
      FiscalYear year = fiscalYearService.findById(fiscalYearId)
      if (year == null) {
        throw new IllegalArgumentException("Okänt räkenskapsår: ${fiscalYearId}")
      }
      retentionPolicyService.ensureDeletionAllowed(year.endDate, "Räkenskapsår ${year.name}")
      long companyId = CompanyService.resolveFromFiscalYear(sql, fiscalYearId)
      new FiscalYearReplacementPlan(
          FiscalYearReplacementService.collectPurgeSummary(sql, companyId, fiscalYearId),
          FiscalYearReplacementService.loadAttachmentStoragePaths(sql, fiscalYearId),
          FiscalYearReplacementService.loadReportArchiveStoragePaths(sql, fiscalYearId)
      )
    }
  }

  FiscalYearDeletionResult deleteFiscalYear(long fiscalYearId) {
    FiscalYearReplacementPlan plan = databaseService.withTransaction { Sql sql ->
      FiscalYear year = fiscalYearService.findById(fiscalYearId)
      if (year == null) {
        throw new IllegalArgumentException("Okänt räkenskapsår: ${fiscalYearId}")
      }
      retentionPolicyService.ensureDeletionAllowed(year.endDate, "Räkenskapsår ${year.name}")
      long companyId = CompanyService.resolveFromFiscalYear(sql, fiscalYearId)

      List<String> attachmentPaths = FiscalYearReplacementService.loadAttachmentStoragePaths(sql, fiscalYearId)
      List<String> reportPaths = FiscalYearReplacementService.loadReportArchiveStoragePaths(sql, fiscalYearId)

      FiscalYearPurgeSummary summary = FiscalYearReplacementService.collectPurgeSummary(sql, companyId, fiscalYearId)
      FiscalYearReplacementService.deleteFiscalYearAuditLogRows(sql, companyId, fiscalYearId)

      sql.executeUpdate('delete from report_archive where fiscal_year_id = ?', [fiscalYearId])

      sql.executeUpdate('update closing_entry set next_fiscal_year_id = null where next_fiscal_year_id = ?', [fiscalYearId])
      sql.executeUpdate('update opening_balance set source_fiscal_year_id = null where source_fiscal_year_id = ?', [fiscalYearId])

      sql.executeUpdate('''
          delete from closing_entry
           where voucher_id in (
               select id from voucher where fiscal_year_id = ?
           )
      ''', [fiscalYearId])
      sql.executeUpdate('delete from closing_entry where fiscal_year_id = ?', [fiscalYearId])

      sql.executeUpdate('update vat_period set transfer_voucher_id = null where fiscal_year_id = ?', [fiscalYearId])
      sql.executeUpdate('delete from vat_period where fiscal_year_id = ?', [fiscalYearId])

      sql.executeUpdate('''
          delete from attachment
           where voucher_id in (
               select id from voucher where fiscal_year_id = ?
           )
      ''', [fiscalYearId])

      sql.executeUpdate('delete from opening_balance where fiscal_year_id = ?', [fiscalYearId])
      sql.executeUpdate('delete from voucher where fiscal_year_id = ?', [fiscalYearId])
      sql.executeUpdate('delete from voucher_series where fiscal_year_id = ?', [fiscalYearId])

      int deleted = sql.executeUpdate('delete from fiscal_year where id = ?', [fiscalYearId])
      if (deleted != 1) {
        throw new IllegalStateException("Räkenskapsåret kunde inte raderas: ${fiscalYearId}")
      }

      auditLogService.recordFiscalYearDeleted(sql, companyId, year.name)

      log.info("Deleted fiscal year ${year.name} (id=${fiscalYearId}): " +
          "${summary.voucherCount} vouchers, ${summary.attachmentCount} attachments, " +
          "${summary.reportArchiveCount} reports, ${summary.vatPeriodCount} VAT periods")

      new FiscalYearReplacementPlan(summary, attachmentPaths, reportPaths)
    }
    StoredFileDeletionReport fileReport = deleteStoredFiles(plan.attachmentStoragePaths, plan.reportArchiveStoragePaths)
    new FiscalYearDeletionResult(
        plan.summary,
        plan.attachmentStoragePaths,
        plan.reportArchiveStoragePaths,
        fileReport.deletedFiles,
        fileReport.failedFiles
    )
  }

  private static StoredFileDeletionReport deleteStoredFiles(
      List<String> attachmentStoragePaths,
      List<String> reportArchiveStoragePaths
  ) {
    StoredFileDeletionReport result = new StoredFileDeletionReport()
    deleteStoredFiles(AppPaths.attachmentsDirectory(), attachmentStoragePaths, result)
    deleteStoredFiles(AppPaths.reportsDirectory(), reportArchiveStoragePaths, result)
    result
  }

  private static void deleteStoredFiles(
      Path rootDirectory,
      List<String> storagePaths,
      StoredFileDeletionReport result
  ) {
    Path root = rootDirectory.toAbsolutePath().normalize()
    storagePaths.findAll { String path -> path != null && !path.isBlank() }.each { String storagePath ->
      Path resolved = root.resolve(storagePath).normalize()
      if (!resolved.startsWith(root)) {
        result.failedFiles << new StoredFileDeletionFailure(
            storagePath,
            resolved.toString(),
            'Lagringsvägen ligger utanför arkivkatalogen.'
        )
      } else {
        try {
          if (Files.deleteIfExists(resolved)) {
            result.deletedFiles << resolved.toString()
          }
        } catch (IOException exception) {
          log.warning("Could not delete stored file ${resolved}: ${exception.message}")
          result.failedFiles << new StoredFileDeletionFailure(
              storagePath,
              resolved.toString(),
              exception.message
          )
        }
      }
    }
  }

  private static final class StoredFileDeletionReport {

    final List<String> deletedFiles = []
    final List<StoredFileDeletionFailure> failedFiles = []
  }
}
