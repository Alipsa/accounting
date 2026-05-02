package se.alipsa.accounting.service

import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.transform.PackageScope

import se.alipsa.accounting.domain.FiscalYear

import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger

/**
 * Handles destructive fiscal-year replacement steps for SIE imports.
 */
final class FiscalYearReplacementService {

  private static final Logger log = Logger.getLogger(FiscalYearReplacementService.name)

  private FiscalYearReplacementService() {
  }

  static FiscalYearReplacementPlan replaceFiscalYearContents(Sql sql, long companyId, FiscalYear fiscalYear) {
    ensureReplaceAllowed(sql, fiscalYear)
    FiscalYearPurgeSummary summary = collectPurgeSummary(sql, companyId, fiscalYear.id)
    List<String> attachmentStoragePaths = loadAttachmentStoragePaths(sql, fiscalYear.id)
    List<String> reportArchiveStoragePaths = loadReportArchiveStoragePaths(sql, fiscalYear.id)
    archiveFiscalYearAuditLogRows(sql, companyId, fiscalYear.id)
    sql.executeUpdate('delete from report_archive where fiscal_year_id = ?', [fiscalYear.id])
    sql.executeUpdate('delete from vat_period where fiscal_year_id = ?', [fiscalYear.id])
    sql.executeUpdate('''
        delete from attachment
         where voucher_id in (
             select id
               from voucher
              where fiscal_year_id = ?
         )
    ''', [fiscalYear.id])
    sql.executeUpdate('delete from opening_balance where fiscal_year_id = ?', [fiscalYear.id])
    sql.executeUpdate('delete from voucher where fiscal_year_id = ?', [fiscalYear.id])
    sql.executeUpdate('delete from voucher_series where fiscal_year_id = ?', [fiscalYear.id])
    sql.executeUpdate('''
        update accounting_period
           set locked = false,
               lock_reason = null,
               locked_at = null
         where fiscal_year_id = ?
    ''', [fiscalYear.id])
    new FiscalYearReplacementPlan(summary, attachmentStoragePaths, reportArchiveStoragePaths)
  }

  static FiscalYearReplacementPlan previewFiscalYearReplacement(Sql sql, long companyId, FiscalYear fiscalYear) {
    ensureReplaceAllowed(sql, fiscalYear)
    new FiscalYearReplacementPlan(
        collectPurgeSummary(sql, companyId, fiscalYear.id),
        loadAttachmentStoragePaths(sql, fiscalYear.id),
        loadReportArchiveStoragePaths(sql, fiscalYear.id)
    )
  }

  static void deleteStoredFilesQuietly(Path rootDirectory, List<String> storagePaths) {
    Path root = rootDirectory.toAbsolutePath().normalize()
    List<String> validPaths = storagePaths.findAll { String path -> path != null && !path.isBlank() }
    if (!validPaths.isEmpty()) {
      log.info("Scheduled deletion of ${validPaths.size()} files under ${root}: ${validPaths}")
    }
    validPaths.each { String storagePath ->
      Path resolved = root.resolve(storagePath).normalize()
      if (!resolved.startsWith(root)) {
        return
      }
      try {
        Files.deleteIfExists(resolved)
      } catch (IOException ex) {
        log.warning("Could not delete stored file ${resolved}: ${ex.message}")
      }
    }
  }

  private static void ensureReplaceAllowed(Sql sql, FiscalYear fiscalYear) {
    if (fiscalYear.closed) {
      throw new IllegalStateException("Räkenskapsåret ${fiscalYear.name} är stängt. Lås upp året innan du ersätter innehållet från SIE.")
    }
    GroovyRowResult closingRow = sql.firstRow(
        'select count(*) as total from closing_entry where fiscal_year_id = ?',
        [fiscalYear.id]
    ) as GroovyRowResult
    if (((Number) closingRow.get('total')).intValue() > 0) {
      throw new IllegalStateException(
          "Räkenskapsåret ${fiscalYear.name} har bokslutsposter och kan inte ersättas från SIE."
      )
    }
  }

  @PackageScope
  static FiscalYearPurgeSummary collectPurgeSummary(Sql sql, long companyId, long fiscalYearId) {
    new FiscalYearPurgeSummary(
        countRows(sql, '''
            select count(*) as total
              from attachment
             where voucher_id in (
                 select id
                   from voucher
                  where fiscal_year_id = ?
             )
        ''', [fiscalYearId]),
        countRows(sql, 'select count(*) as total from report_archive where fiscal_year_id = ?', [fiscalYearId]),
        countRows(sql, 'select count(*) as total from opening_balance where fiscal_year_id = ?', [fiscalYearId]),
        countRows(sql, 'select count(*) as total from voucher where fiscal_year_id = ?', [fiscalYearId]),
        countRows(sql, 'select count(*) as total from vat_period where fiscal_year_id = ?', [fiscalYearId]),
        countRows(sql, '''
            select count(*) as total
              from audit_log
             where company_id = ?
               and archived = false
               and (
                   fiscal_year_id = ?
                   or accounting_period_id in (
                       select id from accounting_period where fiscal_year_id = ?
                   )
                   or vat_period_id in (
                       select id from vat_period where fiscal_year_id = ?
                   )
                   or voucher_id in (
                       select id from voucher where fiscal_year_id = ?
                   )
                   or attachment_id in (
                       select a.id
                         from attachment a
                         join voucher v on v.id = a.voucher_id
                        where v.fiscal_year_id = ?
                   )
               )
        ''', [companyId, fiscalYearId, fiscalYearId, fiscalYearId, fiscalYearId, fiscalYearId])
    )
  }

  @PackageScope
  static List<String> loadAttachmentStoragePaths(Sql sql, long fiscalYearId) {
    sql.rows('''
        select a.storage_path as storagePath
          from attachment a
          join voucher v on v.id = a.voucher_id
         where v.fiscal_year_id = ?
    ''', [fiscalYearId]).collect { GroovyRowResult row ->
      row.get('storagePath') as String
    }
  }

  @PackageScope
  static List<String> loadReportArchiveStoragePaths(Sql sql, long fiscalYearId) {
    sql.rows('''
        select storage_path as storagePath
          from report_archive
         where fiscal_year_id = ?
    ''', [fiscalYearId]).collect { GroovyRowResult row ->
      row.get('storagePath') as String
    }
  }

  @PackageScope
  static void archiveFiscalYearAuditLogRows(Sql sql, long companyId, long fiscalYearId) {
    sql.executeUpdate('''
        update audit_log
           set archived = true,
               fiscal_year_id = null,
               accounting_period_id = null,
               vat_period_id = null,
               voucher_id = null,
               attachment_id = null
         where company_id = ?
           and archived = false
           and (
               fiscal_year_id = ?
               or accounting_period_id in (
                   select id from accounting_period where fiscal_year_id = ?
               )
               or vat_period_id in (
                   select id from vat_period where fiscal_year_id = ?
               )
               or voucher_id in (
                   select id from voucher where fiscal_year_id = ?
               )
               or attachment_id in (
                   select a.id
                     from attachment a
                     join voucher v on v.id = a.voucher_id
                    where v.fiscal_year_id = ?
               )
           )
    ''', [companyId, fiscalYearId, fiscalYearId, fiscalYearId, fiscalYearId, fiscalYearId])
    // Reset the chain head to the latest surviving live entry only.
    // Archived rows are excluded so new audit events do not chain off archived history.
    GroovyRowResult chainRow = sql.firstRow('''
        select entry_hash as entryHash
          from audit_log
         where company_id = ?
           and archived = false
         order by id desc
         limit 1
    ''', [companyId]) as GroovyRowResult
    String newHeadHash = chainRow == null ? null : chainRow.get('entryHash') as String
    sql.executeUpdate('''
        update audit_log_chain_head
           set last_entry_hash = ?,
               updated_at = current_timestamp
         where company_id = ?
    ''', [newHeadHash, companyId])
  }

  @PackageScope
  static int deleteFiscalYearAuditLogRows(Sql sql, long companyId, long fiscalYearId) {
    int deleted = sql.executeUpdate('''
        delete from audit_log
         where company_id = ?
           and (
               fiscal_year_id = ?
               or accounting_period_id in (
                   select id from accounting_period where fiscal_year_id = ?
               )
               or vat_period_id in (
                   select id from vat_period where fiscal_year_id = ?
               )
               or voucher_id in (
                   select id from voucher where fiscal_year_id = ?
               )
               or attachment_id in (
                   select a.id
                     from attachment a
                     join voucher v on v.id = a.voucher_id
                    where v.fiscal_year_id = ?
               )
           )
    ''', [companyId, fiscalYearId, fiscalYearId, fiscalYearId, fiscalYearId, fiscalYearId])
    AuditLogService.rebuildIntegrityChain(sql, companyId)
    deleted
  }

  private static int countRows(Sql sql, String query, List<Object> params) {
    GroovyRowResult row = sql.firstRow(query, params) as GroovyRowResult
    ((Number) row.get('total')).intValue()
  }
}
