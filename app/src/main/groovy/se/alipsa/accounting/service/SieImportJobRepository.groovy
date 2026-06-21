package se.alipsa.accounting.service

import groovy.sql.GroovyRowResult
import groovy.sql.Sql

import se.alipsa.accounting.domain.ImportJob
import se.alipsa.accounting.domain.ImportJobStatus

/**
 * Persists SIE import job lifecycle state.
 */
final class SieImportJobRepository {

  private final DatabaseService databaseService

  SieImportJobRepository(DatabaseService databaseService) {
    this.databaseService = databaseService
  }

  long create(long companyId, String fileName, String checksum) {
    databaseService.withTransaction { Sql sql ->
      List<List<Object>> keys = sql.executeInsert('''
          insert into import_job (
              company_id,
              file_name,
              checksum_sha256,
              status,
              summary,
              error_log,
              started_at,
              completed_at
          ) values (?, ?, ?, 'STARTED', ?, null, current_timestamp, null)
      ''', [companyId, truncate(fileName, 255), checksum, 'Import startad.'])
      ((Number) keys.first().first()).longValue()
    }
  }

  ImportJob markDuplicateIfNeeded(long jobId, long companyId, String checksum) {
    databaseService.withTransaction { Sql sql ->
      GroovyRowResult existing = sql.firstRow('''
          select id,
                 file_name as fileName
            from import_job
           where checksum_sha256 = ?
             and company_id = ?
             and status = 'SUCCESS'
             and id <> ?
           order by completed_at desc, id desc
           limit 1
      ''', [checksum, companyId, jobId]) as GroovyRowResult
      if (existing == null) {
        return null
      }
      String summary = "Filen har redan importerats tidigare (jobb ${existing.get('id')})."
      complete(sql, jobId, null, ImportJobStatus.DUPLICATE, summary, [])
    }
  }

  static ImportJob findSuccessfulByChecksum(Sql sql, long companyId, String checksum) {
    GroovyRowResult row = sql.firstRow('''
        select id,
               file_name as fileName,
               checksum_sha256 as checksumSha256,
               fiscal_year_id as fiscalYearId,
               status,
               summary,
               error_log as errorLog,
               started_at as startedAt,
               completed_at as completedAt
          from import_job
         where checksum_sha256 = ?
           and company_id = ?
           and status = 'SUCCESS'
         order by completed_at desc, id desc
         limit 1
    ''', [checksum, companyId]) as GroovyRowResult
    row == null ? null : mapImportJob(row)
  }

  static ImportJob complete(
      Sql sql,
      long jobId,
      Long fiscalYearId,
      ImportJobStatus status,
      String summary,
      List<String> warnings
  ) {
    String errorLog = warnings == null || warnings.isEmpty() ? null : truncate(warnings.join('\n'), 20000)
    sql.executeUpdate('''
        update import_job
           set fiscal_year_id = ?,
               status = ?,
               summary = ?,
               error_log = ?,
               completed_at = current_timestamp
         where id = ?
    ''', [fiscalYearId, status.name(), truncate(summary, 1000), errorLog, jobId])
    find(sql, jobId)
  }

  void markFailed(long jobId, Long fiscalYearId, Exception exception) {
    databaseService.withTransaction { Sql sql ->
      sql.executeUpdate('''
          update import_job
             set fiscal_year_id = coalesce(?, fiscal_year_id),
                 status = 'FAILED',
                 summary = ?,
                 error_log = ?,
                 completed_at = current_timestamp
           where id = ?
      ''', [
          fiscalYearId,
          truncate(exception.message ?: 'Importen misslyckades.', 1000),
          truncate(buildFailureLog(exception), 20000),
          jobId
      ])
    }
  }

  List<ImportJob> list(long companyId, int limit) {
    int safeLimit = Math.max(1, limit)
    databaseService.withSql { Sql sql ->
      sql.rows('''
          select ij.id,
                 ij.file_name as fileName,
                 ij.checksum_sha256 as checksumSha256,
                 ij.fiscal_year_id as fiscalYearId,
                 ij.status,
                 ij.summary,
                 ij.error_log as errorLog,
                 ij.started_at as startedAt,
                 ij.completed_at as completedAt
            from import_job ij
           where ij.company_id = ?
           order by ij.started_at desc, ij.id desc
           limit ?
      ''', [companyId, safeLimit]).collect { GroovyRowResult row ->
        mapImportJob(row)
      }
    }
  }

  private static ImportJob find(Sql sql, long jobId) {
    GroovyRowResult row = sql.firstRow('''
        select id,
               file_name as fileName,
               checksum_sha256 as checksumSha256,
               fiscal_year_id as fiscalYearId,
               status,
               summary,
               error_log as errorLog,
               started_at as startedAt,
               completed_at as completedAt
          from import_job
         where id = ?
    ''', [jobId]) as GroovyRowResult
    row == null ? null : mapImportJob(row)
  }

  static ImportJob mapImportJob(GroovyRowResult row) {
    new ImportJob(
        Long.valueOf(row.get('id').toString()),
        row.get('fileName') as String,
        row.get('checksumSha256') as String,
        row.get('fiscalYearId') == null ? null : Long.valueOf(row.get('fiscalYearId').toString()),
        ImportJobStatus.valueOf(row.get('status') as String),
        row.get('summary') as String,
        SqlValueMapper.toClob(row.get('errorLog')),
        SqlValueMapper.toLocalDateTime(row.get('startedAt')),
        SqlValueMapper.toLocalDateTime(row.get('completedAt'))
    )
  }

  private static String buildFailureLog(Throwable throwable) {
    List<String> parts = []
    Throwable current = throwable
    while (current != null) {
      if (current.message) {
        parts << current.message
      } else {
        parts << current.class.simpleName
      }
      current = current.cause
    }
    parts.unique().join('\n')
  }

  private static String truncate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value
    }
    value.substring(0, maxLength)
  }
}
