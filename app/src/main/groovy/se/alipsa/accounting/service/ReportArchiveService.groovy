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
import java.time.format.DateTimeFormatter
import java.util.logging.Logger
import java.util.stream.Stream

/**
 * Stores generated report artifacts on disk and keeps archive metadata in the database.
 */
final class ReportArchiveService {

  private static final Logger log = Logger.getLogger(ReportArchiveService.name)

  private final DatabaseService databaseService
  private final RetentionPolicyService retentionPolicyService
  private final ReportArchiveFileOperations fileOperations

  ReportArchiveService() {
    this(DatabaseService.instance)
  }

  ReportArchiveService(DatabaseService databaseService) {
    this(databaseService, new RetentionPolicyService())
  }

  ReportArchiveService(DatabaseService databaseService, RetentionPolicyService retentionPolicyService) {
    this(databaseService, retentionPolicyService, new DefaultReportArchiveFileOperations())
  }

  ReportArchiveService(
      DatabaseService databaseService,
      RetentionPolicyService retentionPolicyService,
      ReportArchiveFileOperations fileOperations
  ) {
    this.databaseService = databaseService
    this.retentionPolicyService = retentionPolicyService
    this.fileOperations = fileOperations
  }

  ReportArchive archiveReport(ReportSelection selection, String reportFormat, byte[] content) {
    ReportSelection safeSelection = requireSelection(selection)
    String safeFormat = normalizeFormat(reportFormat)
    AppPaths.ensureDirectoryStructure()
    String fileName = buildFileName(safeSelection, safeFormat)

    long companyId = databaseService.withSql { Sql sql ->
      CompanyService.resolveFromFiscalYear(sql, safeSelection.fiscalYearId)
    }
    String storagePath = buildStoragePath(companyId, fileName)
    Path targetPath = resolveStoragePath(storagePath)
    Files.createDirectories(targetPath.parent)
    fileOperations.write(targetPath, content)
    String checksum = calculateChecksum(content)

    try {
      databaseService.withTransaction { Sql sql ->
        LocalDateTime createdAt = currentDatabaseTimestamp(sql)
        List<List<Object>> keys = sql.executeInsert('''
            insert into report_archive (
                company_id,
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
                created_at,
                status
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ''', [
            companyId,
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
            Timestamp.valueOf(createdAt),
            'ACTIVE'
        ])
        long archiveId = ((Number) keys.first().first()).longValue()
        findArchive(sql, archiveId)
      }
    } catch (Throwable throwable) {
      fileOperations.deleteIfExists(targetPath)
      throw throwable
    }
  }

  List<ReportArchive> listArchives(long companyId, int limit = 100) {
    CompanyService.requireValidCompanyId(companyId)
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
                 ra.created_at as createdAt,
                 ra.status
            from report_archive ra
            join fiscal_year fy on fy.id = ra.fiscal_year_id
           where fy.company_id = ?
             and ra.status = 'ACTIVE'
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
                 created_at as createdAt,
                 status
            from report_archive
           where status = 'ACTIVE'
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
    fileOperations.readAllBytes(resolveStoredPath(archive))
  }

  boolean verifyArchive(long archiveId) {
    ReportArchive archive = findArchive(archiveId)
    if (archive == null) {
      throw new IllegalArgumentException("Okänt rapportarkiv: ${archiveId}")
    }
    verifyArchive(archive)
  }

  List<ReportArchive> findIntegrityFailures(long companyId) {
    CompanyService.requireValidCompanyId(companyId)
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
                 ra.created_at as createdAt,
                 ra.status
            from report_archive ra
            join fiscal_year fy on fy.id = ra.fiscal_year_id
           where fy.company_id = ?
             and ra.status = 'ACTIVE'
           order by ra.id
      ''', [companyId]).collect { GroovyRowResult row ->
        mapArchive(row)
      }.findAll { ReportArchive archive ->
        !verifyArchive(archive)
      }
    }
  }

  List<ReportArchive> findAllIntegrityFailures() {
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
                 created_at as createdAt,
                 status
            from report_archive
           where status = 'ACTIVE'
           order by id
      ''').collect { GroovyRowResult row ->
        mapArchive(row)
      }.findAll { ReportArchive archive ->
        !verifyArchive(archive)
      }
    }
  }

  ReportArchive findArchive(long archiveId) {
    databaseService.withSql { Sql sql ->
      findArchive(sql, archiveId)
    }
  }

  ReportArchiveRecoveryReport recoverOnStartup() {
    int deletionsDone = 0
    List<String> warnings = []

    databaseService.withTransaction { Sql sql ->
      List<ReportArchive> pendingDelete = sql.rows('''
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
                 created_at as createdAt,
                 status
            from report_archive
           where status = 'PENDING_DELETE'
           order by id
      ''').collect { GroovyRowResult row ->
        mapArchive(row)
      }

      pendingDelete.each { ReportArchive archive ->
        Path path = resolveStoragePath(archive.storagePath)
        tryDeleteQuietly(path)
        if (!fileOperations.isRegularFile(path)) {
          int updated = sql.executeUpdate(
              'update report_archive set status = ? where id = ? and status = ?',
              ['DELETED', archive.id, 'PENDING_DELETE']
          )
          if (updated == 1) {
            deletionsDone++
          }
        } else {
          String warning = "Rapportarkiv ${archive.id} kunde inte raderas vid återställning; lämnas som PENDING_DELETE."
          log.warning(warning)
          warnings << warning
        }
      }
    }

    List<Path> orphanFiles = findOrphanFiles()
    new ReportArchiveRecoveryReport(deletionsDone, orphanFiles, warnings)
  }

  private List<Path> findOrphanFiles() {
    Path root = AppPaths.reportsDirectory().toAbsolutePath().normalize()
    if (!Files.isDirectory(root)) {
      return []
    }

    Set<String> knownPaths = databaseService.withSql { Sql sql ->
      sql.rows('''
          select storage_path as storagePath
            from report_archive
           where status != 'DELETED'
      ''').collect { GroovyRowResult row ->
        ((String) row.get('storagePath')).replace('\\', '/')
      } as Set
    }

    List<Path> orphans = []
    try (Stream<Path> stream = fileOperations.walk(root)) {
      stream.each { Path path ->
        if (fileOperations.isRegularFile(path)) {
          String relative = root.relativize(path).toString().replace('\\', '/')
          if (!knownPaths.contains(relative)) {
            orphans << path
          }
        }
      }
    }
    orphans
  }

  private void tryDeleteQuietly(Path path) {
    try {
      fileOperations.deleteIfExists(path)
    } catch (IOException ignored) {
      // best-effort cleanup
    }
  }

  void deleteArchive(long archiveId) {
    ReportArchive archive = findArchive(archiveId)
    if (archive == null) {
      throw new IllegalArgumentException("Okänt rapportarkiv: ${archiveId}")
    }
    if (archive.status != 'ACTIVE') {
      throw new IllegalArgumentException("Rapportarkiv ${archiveId} är inte aktivt (status=${archive.status}).")
    }
    retentionPolicyService.ensureDeletionAllowed(archive.createdAt, "Rapportarkiv ${archive.fileName}")
    databaseService.withTransaction { Sql sql ->
      int updated = sql.executeUpdate(
          'update report_archive set status = ? where id = ? and status = ?',
          ['PENDING_DELETE', archiveId, 'ACTIVE']
      )
      if (updated != 1) {
        throw new IllegalStateException("Rapportarkivet kunde inte markeras för borttagning: ${archiveId}")
      }
    }
    Path targetPath = resolveStoredPath(archive)
    try {
      fileOperations.deleteIfExists(targetPath)
    } catch (Exception exception) {
      log.warning("Kunde inte radera rapportarkivfilen ${targetPath} för arkiv ${archiveId}: ${exception.message}")
      return
    }
    databaseService.withTransaction { Sql sql ->
      int updated = sql.executeUpdate(
          'update report_archive set status = ? where id = ? and status = ?',
          ['DELETED', archiveId, 'PENDING_DELETE']
      )
      if (updated != 1) {
        throw new IllegalStateException("Rapportarkivet kunde inte slutföras som borttaget: ${archiveId}")
      }
    }
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
               created_at as createdAt,
               status
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
        SqlValueMapper.toClob(row.get('parameters')),
        SqlValueMapper.toLocalDateTime(row.get('createdAt')),
        row.get('status') as String
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
    if (!(safeFormat in ['PDF', 'CSV', 'XLSX'])) {
      throw new IllegalArgumentException("Ogiltigt rapportformat: ${reportFormat}")
    }
    safeFormat
  }

  private static String buildFileName(ReportSelection selection, String reportFormat) {
    String suffix = reportFormat.toLowerCase(Locale.ROOT)
    "${selection.reportType.name().toLowerCase(Locale.ROOT)}-${selection.startDate}-${selection.endDate}.${suffix}"
  }

  private static final DateTimeFormatter STORAGE_TIMESTAMP_FORMATTER =
      DateTimeFormatter.ofPattern('yyyyMMdd-HHmmssSSS')

  private static String buildStoragePath(long companyId, String fileName) {
    LocalDateTime now = LocalDateTime.now()
    String safeFileName = fileName.replaceAll(/[^A-Za-z0-9._-]/, '_')
    String timestamp = now.format(STORAGE_TIMESTAMP_FORMATTER)
    int dot = safeFileName.lastIndexOf('.')
    String stem = dot > 0 ? safeFileName.substring(0, dot) : safeFileName
    String ext = dot > 0 ? safeFileName.substring(dot) : ''
    "c${companyId}/${now.year}/${String.format('%02d', now.monthValue)}/${stem}-${timestamp}${ext}"
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

  private boolean verifyArchive(ReportArchive archive) {
    Path path
    try {
      path = resolveStoragePath(archive.storagePath)
    } catch (SecurityException ex) {
      log.warning("Arkivets lagringsväg misslyckades vid säkerhetskontroll: ${archive.storagePath} – ${ex.message}")
      return false
    }
    if (!fileOperations.isRegularFile(path)) {
      return false
    }
    calculateChecksum(fileOperations.readAllBytes(path)) == archive.checksumSha256
  }
}
