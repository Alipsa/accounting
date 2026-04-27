package se.alipsa.accounting.service

import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.transform.PackageScope

import se.alipsa.accounting.domain.FiscalYear

import java.sql.Date
import java.time.LocalDate

/**
 * Creates fiscal years, prevents overlap and supports year close.
 */
final class FiscalYearService {

  private final DatabaseService databaseService
  private final AccountingPeriodService accountingPeriodService
  private final AuditLogService auditLogService

  FiscalYearService() {
    this(
        DatabaseService.instance,
        new AccountingPeriodService(DatabaseService.instance),
        new AuditLogService(DatabaseService.instance)
    )
  }

  FiscalYearService(DatabaseService databaseService) {
    this(
        databaseService,
        new AccountingPeriodService(databaseService),
        new AuditLogService(databaseService)
    )
  }

  FiscalYearService(DatabaseService databaseService, AccountingPeriodService accountingPeriodService) {
    this(databaseService, accountingPeriodService, new AuditLogService(databaseService))
  }

  FiscalYearService(
      DatabaseService databaseService,
      AccountingPeriodService accountingPeriodService,
      AuditLogService auditLogService
  ) {
    this.databaseService = databaseService
    this.accountingPeriodService = accountingPeriodService
    this.auditLogService = auditLogService
  }

  FiscalYear createFiscalYear(long companyId, String name, LocalDate startDate, LocalDate endDate) {
    CompanyService.requireValidCompanyId(companyId)
    databaseService.withTransaction { Sql sql ->
      createFiscalYear(sql, accountingPeriodService, companyId, name, startDate, endDate)
    }
  }

  List<FiscalYear> listFiscalYears(long companyId) {
    CompanyService.requireValidCompanyId(companyId)
    databaseService.withSql { Sql sql ->
      sql.rows('''
                select id,
                       name,
                       start_date as startDate,
                       end_date as endDate,
                       closed,
                       closed_at as closedAt
                  from fiscal_year
                 where company_id = ?
                 order by start_date desc
            ''', [companyId]).collect { GroovyRowResult row ->
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

  FiscalYear reopenFiscalYear(long fiscalYearId) {
    databaseService.withTransaction { Sql sql ->
      reopenFiscalYear(sql, fiscalYearId)
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
      long companyId,
      String name,
      LocalDate startDate,
      LocalDate endDate,
      String overlapMessage = 'Fiscal year dates overlap an existing fiscal year.'
  ) {
    validateDateRange(startDate, endDate)
    String safeName = resolveName(name, startDate, endDate)
    ensureNoOverlap(sql, companyId, startDate, endDate, overlapMessage)
    List<List<Object>> keys = sql.executeInsert('''
                insert into fiscal_year (
                    company_id,
                    name,
                    start_date,
                    end_date,
                    closed,
                    closed_at,
                    created_at
                ) values (?, ?, ?, ?, false, null, current_timestamp)
            ''', [companyId, safeName, Date.valueOf(startDate), Date.valueOf(endDate)])
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

  @PackageScope
  FiscalYear reopenFiscalYear(Sql sql, long fiscalYearId) {
    FiscalYear year = findById(sql, fiscalYearId)
    if (year == null) {
      throw new IllegalArgumentException("Unknown fiscal year id: ${fiscalYearId}")
    }
    if (!year.closed) {
      return findById(sql, fiscalYearId)
    }
    GroovyRowResult closingRow = sql.firstRow(
        'select count(*) as total from closing_entry where fiscal_year_id = ?',
        [fiscalYearId]
    ) as GroovyRowResult
    if (((Number) closingRow.get('total')).intValue() > 0) {
      throw new IllegalStateException(
          "Räkenskapsåret ${year.name} har bokslutsposter och kan inte låsas upp. " +
          'Ta bort bokslutposterna innan du öppnar upp räkenskapsåret igen.'
      )
    }
    sql.executeUpdate('''
                update fiscal_year
                   set closed = false,
                       closed_at = null
                 where id = ?
            ''', [fiscalYearId])
    FiscalYear reopenedYear = findById(sql, fiscalYearId)
    auditLogService.recordFiscalYearReopened(sql, reopenedYear)
    reopenedYear
  }

  private static void ensureNoOverlap(Sql sql, long companyId, LocalDate startDate, LocalDate endDate, String overlapMessage) {
    GroovyRowResult row = sql.firstRow('''
            select count(*) as total
              from fiscal_year
             where company_id = ?
               and start_date <= ?
               and end_date >= ?
        ''', [companyId, Date.valueOf(endDate), Date.valueOf(startDate)]) as GroovyRowResult
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
