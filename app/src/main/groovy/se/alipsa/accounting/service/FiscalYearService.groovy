package se.alipsa.accounting.service

import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.transform.CompileStatic
import groovy.transform.PackageScope

import se.alipsa.accounting.domain.FiscalYear

import java.sql.Date
import java.time.LocalDate

/**
 * Creates fiscal years, prevents overlap and supports year close.
 */
@CompileStatic
final class FiscalYearService {

  private final DatabaseService databaseService
  private final AccountingPeriodService accountingPeriodService
  private final AuditLogService auditLogService
  private final RetentionPolicyService retentionPolicyService

  FiscalYearService() {
    this(
        DatabaseService.instance,
        new AccountingPeriodService(DatabaseService.instance),
        new AuditLogService(DatabaseService.instance),
        new RetentionPolicyService()
    )
  }

  FiscalYearService(DatabaseService databaseService) {
    this(
        databaseService,
        new AccountingPeriodService(databaseService),
        new AuditLogService(databaseService),
        new RetentionPolicyService()
    )
  }

  FiscalYearService(DatabaseService databaseService, AccountingPeriodService accountingPeriodService) {
    this(databaseService, accountingPeriodService, new AuditLogService(databaseService), new RetentionPolicyService())
  }

  FiscalYearService(
      DatabaseService databaseService,
      AccountingPeriodService accountingPeriodService,
      AuditLogService auditLogService
  ) {
    this(databaseService, accountingPeriodService, auditLogService, new RetentionPolicyService())
  }

  FiscalYearService(
      DatabaseService databaseService,
      AccountingPeriodService accountingPeriodService,
      AuditLogService auditLogService,
      RetentionPolicyService retentionPolicyService
  ) {
    this.databaseService = databaseService
    this.accountingPeriodService = accountingPeriodService
    this.auditLogService = auditLogService
    this.retentionPolicyService = retentionPolicyService
  }

  FiscalYear createFiscalYear(String name, LocalDate startDate, LocalDate endDate) {
    databaseService.withTransaction { Sql sql ->
      createFiscalYear(sql, accountingPeriodService, name, startDate, endDate)
    }
  }

  List<FiscalYear> listFiscalYears() {
    databaseService.withSql { Sql sql ->
      sql.rows('''
                select id,
                       name,
                       start_date as startDate,
                       end_date as endDate,
                       closed,
                       closed_at as closedAt
                  from fiscal_year
                 order by start_date desc
            ''').collect { GroovyRowResult row ->
        mapFiscalYear(row)
      }
    }
  }

  FiscalYear findById(long fiscalYearId) {
    databaseService.withSql { Sql sql ->
      findById(sql, fiscalYearId)
    }
  }

  FiscalYear closeFiscalYear(long fiscalYearId) {
    databaseService.withTransaction { Sql sql ->
      closeFiscalYear(sql, fiscalYearId)
    }
  }

  void deleteFiscalYear(long fiscalYearId) {
    databaseService.withTransaction { Sql sql ->
      FiscalYear year = findById(sql, fiscalYearId)
      if (year == null) {
        throw new IllegalArgumentException("Unknown fiscal year id: ${fiscalYearId}")
      }
      retentionPolicyService.ensureDeletionAllowed(year.endDate, "Räkenskapsår ${year.name}")
      int deleted = sql.executeUpdate('delete from fiscal_year where id = ?', [fiscalYearId])
      if (deleted != 1) {
        throw new IllegalStateException("Räkenskapsåret kunde inte raderas: ${fiscalYearId}")
      }
    }
  }

  private static void validateDateRange(LocalDate startDate, LocalDate endDate) {
    if (startDate == null || endDate == null) {
      throw new IllegalArgumentException('Start and end date are required.')
    }
    if (endDate.isBefore(startDate)) {
      throw new IllegalArgumentException('End date must be on or after start date.')
    }
  }

  private static String resolveName(String name, LocalDate startDate, LocalDate endDate) {
    String trimmed = name?.trim()
    trimmed ?: "${startDate} - ${endDate}"
  }

  static FiscalYear createFiscalYear(
      Sql sql,
      AccountingPeriodService accountingPeriodService,
      String name,
      LocalDate startDate,
      LocalDate endDate,
      String overlapMessage = 'Fiscal year dates overlap an existing fiscal year.'
  ) {
    validateDateRange(startDate, endDate)
    String safeName = resolveName(name, startDate, endDate)
    ensureNoOverlap(sql, startDate, endDate, overlapMessage)
    List<List<Object>> keys = sql.executeInsert('''
                insert into fiscal_year (
                    name,
                    start_date,
                    end_date,
                    closed,
                    closed_at,
                    created_at
                ) values (?, ?, ?, false, null, current_timestamp)
            ''', [safeName, Date.valueOf(startDate), Date.valueOf(endDate)])
    long fiscalYearId = ((Number) keys.first().first()).longValue()
    accountingPeriodService.createPeriods(sql, fiscalYearId, startDate, endDate)
    VatService.ensurePeriodsForFiscalYear(sql, fiscalYearId)
    findById(sql, fiscalYearId)
  }

  @PackageScope
  FiscalYear closeFiscalYear(Sql sql, long fiscalYearId) {
    FiscalYear year = findById(sql, fiscalYearId)
    if (year == null) {
      throw new IllegalArgumentException("Unknown fiscal year id: ${fiscalYearId}")
    }
    if (year.closed) {
      return findById(sql, fiscalYearId)
    }
    GroovyRowResult row = sql.firstRow('''
        select count(*) as total
          from accounting_period
         where fiscal_year_id = ?
           and locked = false
    ''', [fiscalYearId]) as GroovyRowResult
    if (((Number) row.get('total')).intValue() > 0) {
      throw new IllegalStateException('Räkenskapsåret kan inte stängas förrän alla perioder är låsta.')
    }
    sql.executeUpdate('''
                update fiscal_year
                   set closed = true,
                       closed_at = current_timestamp
                 where id = ?
            ''', [fiscalYearId])
    FiscalYear closedYear = findById(sql, fiscalYearId)
    auditLogService.recordFiscalYearClosed(sql, closedYear)
    closedYear
  }

  private static void ensureNoOverlap(Sql sql, LocalDate startDate, LocalDate endDate, String overlapMessage) {
    GroovyRowResult row = sql.firstRow('''
            select count(*) as total
              from fiscal_year
             where start_date <= ?
               and end_date >= ?
        ''', [Date.valueOf(endDate), Date.valueOf(startDate)]) as GroovyRowResult
    if (((Number) row.get('total')).intValue() > 0) {
      throw new IllegalArgumentException(overlapMessage)
    }
  }

  private static FiscalYear findById(Sql sql, long fiscalYearId) {
    GroovyRowResult row = sql.firstRow('''
            select id,
                   name,
                   start_date as startDate,
                   end_date as endDate,
                   closed,
                   closed_at as closedAt
              from fiscal_year
             where id = ?
        ''', [fiscalYearId]) as GroovyRowResult
    row == null ? null : mapFiscalYear(row)
  }

  private static FiscalYear mapFiscalYear(GroovyRowResult row) {
    new FiscalYear(
        Long.valueOf(row.get('id').toString()),
        row.get('name') as String,
        SqlValueMapper.toLocalDate(row.get('startDate')),
        SqlValueMapper.toLocalDate(row.get('endDate')),
        Boolean.TRUE == row.get('closed'),
        SqlValueMapper.toLocalDateTime(row.get('closedAt'))
    )
  }
}
