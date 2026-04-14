package se.alipsa.accounting.service

import groovy.sql.GroovyRowResult
import groovy.sql.Sql

import se.alipsa.accounting.domain.report.ReportArchive
import se.alipsa.accounting.domain.report.ReportSelection
import se.alipsa.accounting.domain.report.ReportType
import se.alipsa.accounting.support.AppPaths

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Date
import java.sql.Timestamp
import java.time.LocalDateTime

/**
 * Stores generated report artifacts on disk and keeps archive metadata in the database.
 */
final class ReportArchiveService {

  private final DatabaseService databaseService
  private final RetentionPolicyService retentionPolicyService

  ReportArchiveService() {
    this(DatabaseService.instance)
  }

  ReportArchiveService(DatabaseService databaseService) {
    this(databaseService, new RetentionPolicyService())
  }

  ReportArchiveService(DatabaseService databaseService, RetentionPolicyService retentionPolicyService) {
    this.databaseService = databaseService
    this.retentionPolicyService = retentionPolicyService
  }

  ReportArchive archiveReport(ReportSelection selection, String reportFormat, byte[] content) {
    ReportSelection safeSelection = requireSelection(selection)
    String safeFormat = normalizeFormat(reportFormat)
    AppPaths.ensureDirectoryStructure()
    String fileName = buildFileName(safeSelection, safeFormat)
    String storagePath = buildStoragePath(fileName)
    Path targetPath = resolveStoragePath(storagePath)
    Files.createDirectories(targetPath.parent)
    Files.write(targetPath, content)
    String checksum = calculateChecksum(content)

    try {
      databaseService.withTransaction { Sql sql ->
        LocalDateTime createdAt = currentDatabaseTimestamp(sql)
        List<List<Object>> keys = sql.executeInsert('''
            insert into report_archive (
                report_type,
                report_format,
                fiscal_year_id,
                accounting_period_id,
                start_date,
                end_date,
                file_name,
                storage_path,
                checksum_sha256,
                parameters,
                created_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ''', [
            safeSelection.reportType.name(),
            safeFormat,
            safeSelection.fiscalYearId,
            safeSelection.accountingPeriodId,
            Date.valueOf(safeSelection.startDate),
            Date.valueOf(safeSelection.endDate),
            fileName,
            storagePath,
            checksum,
            formatParameters(safeSelection),
            Timestamp.valueOf(createdAt)
        ])
        long archiveId = ((Number) keys.first().first()).longValue()
        findArchive(sql, archiveId)
      }
    } catch (Throwable throwable) {
      Files.deleteIfExists(targetPath)
      throw throwable
    }
  }

  List<ReportArchive> listArchives(long companyId, int limit = 100) {
    int safeLimit = Math.max(1, limit)
    databaseService.withSql { Sql sql ->
      sql.rows('''
          select ra.id,
                 ra.report_type as reportType,
                 ra.report_format as reportFormat,
                 ra.fiscal_year_id as fiscalYearId,
                 ra.accounting_period_id as accountingPeriodId,
                 ra.start_date as startDate,
                 ra.end_date as endDate,
                 ra.file_name as fileName,
                 ra.storage_path as storagePath,
                 ra.checksum_sha256 as checksumSha256,
                 ra.parameters,
                 ra.created_at as createdAt
            from report_archive ra
            join fiscal_year fy on fy.id = ra.fiscal_year_id
           where fy.company_id = ?
           order by ra.created_at desc, ra.id desc
           limit ?
      ''', [companyId, safeLimit]).collect { GroovyRowResult row ->
        mapArchive(row)
      }
    }
  }

  List<ReportArchive> listAllArchives(int limit = 10_000) {
    int safeLimit = Math.max(1, limit)
    databaseService.withSql { Sql sql ->
      sql.rows('''
          select id,
                 report_type as reportType,
                 report_format as reportFormat,
                 fiscal_year_id as fiscalYearId,
                 accounting_period_id as accountingPeriodId,
                 start_date as startDate,
                 end_date as endDate,
                 file_name as fileName,
                 storage_path as storagePath,
                 checksum_sha256 as checksumSha256,
                 parameters,
                 created_at as createdAt
            from report_archive
           order by created_at desc, id desc
           limit ?
      ''', [safeLimit]).collect { GroovyRowResult row ->
        mapArchive(row)
      }
    }
  }

  Path resolveStoredPath(ReportArchive archive) {
    resolveStoragePath(archive.storagePath)
  }

  byte[] readArchive(long archiveId) {
    ReportArchive archive = findArchive(archiveId)
    if (archive == null) {
      throw new IllegalArgumentException("Okänt rapportarkiv: ${archiveId}")
    }
    Files.readAllBytes(resolveStoredPath(archive))
  }

  boolean verifyArchive(long archiveId) {
    ReportArchive archive = findArchive(archiveId)
    if (archive == null) {
      throw new IllegalArgumentException("Okänt rapportarkiv: ${archiveId}")
    }
    verifyArchive(archive)
  }

  List<ReportArchive> findIntegrityFailures(long companyId) {
    listArchives(companyId, 500).findAll { ReportArchive archive ->
      !verifyArchive(archive)
    }
  }

  List<ReportArchive> findAllIntegrityFailures() {
    listAllArchives(500).findAll { ReportArchive archive ->
      !verifyArchive(archive)
    }
  }

  ReportArchive findArchive(long archiveId) {
    databaseService.withSql { Sql sql ->
      findArchive(sql, archiveId)
    }
  }

  void deleteArchive(long archiveId) {
    ReportArchive archive = findArchive(archiveId)
    if (archive == null) {
      throw new IllegalArgumentException("Okänt rapportarkiv: ${archiveId}")
    }
    retentionPolicyService.ensureDeletionAllowed(archive.createdAt, "Rapportarkiv ${archive.fileName}")
    databaseService.withTransaction { Sql sql ->
      int deleted = sql.executeUpdate('delete from report_archive where id = ?', [archiveId])
      if (deleted != 1) {
        throw new IllegalStateException("Rapportarkivet kunde inte raderas: ${archiveId}")
      }
    }
    Files.deleteIfExists(resolveStoredPath(archive))
  }

  private static ReportArchive findArchive(Sql sql, long archiveId) {
    GroovyRowResult row = sql.firstRow('''
        select id,
               report_type as reportType,
               report_format as reportFormat,
               fiscal_year_id as fiscalYearId,
               accounting_period_id as accountingPeriodId,
               start_date as startDate,
               end_date as endDate,
               file_name as fileName,
               storage_path as storagePath,
               checksum_sha256 as checksumSha256,
               parameters,
               created_at as createdAt
          from report_archive
         where id = ?
    ''', [archiveId]) as GroovyRowResult
    row == null ? null : mapArchive(row)
  }

  private static ReportArchive mapArchive(GroovyRowResult row) {
    new ReportArchive(
        Long.valueOf(row.get('id').toString()),
        ReportType.valueOf(row.get('reportType') as String),
        row.get('reportFormat') as String,
        Long.valueOf(row.get('fiscalYearId').toString()),
        row.get('accountingPeriodId') == null ? null : Long.valueOf(row.get('accountingPeriodId').toString()),
        SqlValueMapper.toLocalDate(row.get('startDate')),
        SqlValueMapper.toLocalDate(row.get('endDate')),
        row.get('fileName') as String,
        row.get('storagePath') as String,
        row.get('checksumSha256') as String,
        row.get('parameters') as String,
        SqlValueMapper.toLocalDateTime(row.get('createdAt'))
    )
  }

  private static ReportSelection requireSelection(ReportSelection selection) {
    if (selection?.reportType == null || selection.fiscalYearId == null || selection.startDate == null || selection.endDate == null) {
      throw new IllegalArgumentException('Rapporturvalet är ofullständigt och kan inte arkiveras.')
    }
    selection
  }

  private static String normalizeFormat(String reportFormat) {
    String safeFormat = reportFormat?.trim()?.toUpperCase(Locale.ROOT)
    if (!(safeFormat in ['PDF', 'CSV'])) {
      throw new IllegalArgumentException("Ogiltigt rapportformat: ${reportFormat}")
    }
    safeFormat
  }

  private static String buildFileName(ReportSelection selection, String reportFormat) {
    String suffix = reportFormat.toLowerCase(Locale.ROOT)
    "${selection.reportType.name().toLowerCase(Locale.ROOT)}-${selection.startDate}-${selection.endDate}.${suffix}"
  }

  private static String buildStoragePath(String fileName) {
    LocalDateTime now = LocalDateTime.now()
    String safeFileName = fileName.replaceAll(/[^A-Za-z0-9._-]/, '_')
    "${now.year}/${String.format('%02d', now.monthValue)}/${safeFileName}"
  }

  private static Path resolveStoragePath(String storagePath) {
    Path root = AppPaths.reportsDirectory().toAbsolutePath().normalize()
    Path resolved = root.resolve(storagePath).normalize()
    if (!resolved.startsWith(root)) {
      throw new SecurityException("Rapportens lagringsväg ligger utanför rapportarkivet: ${storagePath}")
    }
    resolved
  }

  private static String formatParameters(ReportSelection selection) {
    [
        "reportType=${selection.reportType.name()}",
        "fiscalYearId=${selection.fiscalYearId}",
        selection.accountingPeriodId == null ? null : "accountingPeriodId=${selection.accountingPeriodId}",
        "startDate=${selection.startDate}",
        "endDate=${selection.endDate}"
    ].findAll { String value -> value != null }.join('\n')
  }

  private static LocalDateTime currentDatabaseTimestamp(Sql sql) {
    GroovyRowResult row = sql.firstRow('select current_timestamp as createdAt') as GroovyRowResult
    SqlValueMapper.toLocalDateTime(row.get('createdAt'))
  }

  private static String calculateChecksum(byte[] content) {
    MessageDigest digest = MessageDigest.getInstance('SHA-256')
    HexFormat.of().formatHex(digest.digest(content))
  }

  private static boolean verifyArchive(ReportArchive archive) {
    Path path
    try {
      path = resolveStoragePath(archive.storagePath)
    } catch (SecurityException ignored) {
      return false
    }
    if (!Files.isRegularFile(path)) {
      return false
    }
    calculateChecksum(Files.readAllBytes(path)) == archive.checksumSha256
  }
}
