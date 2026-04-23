package se.alipsa.accounting.service

import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.transform.PackageScope

import se.alipsa.accounting.domain.AccountingPeriod

import java.sql.Date
import java.time.LocalDate

/**
 * Handles period creation and legacy period-lock metadata.
 */
final class AccountingPeriodService {

  private final DatabaseService databaseService
  private final AuditLogService auditLogService

  AccountingPeriodService() {
    this(DatabaseService.instance)
  }

  AccountingPeriodService(DatabaseService databaseService) {
    this(databaseService, new AuditLogService(databaseService))
  }

  AccountingPeriodService(DatabaseService databaseService, AuditLogService auditLogService) {
    this.databaseService = databaseService
    this.auditLogService = auditLogService
  }

  List<AccountingPeriod> listPeriods(long fiscalYearId) {
    databaseService.withSql { Sql sql ->
      sql.rows('''
                select id,
                       fiscal_year_id as fiscalYearId,
                       period_index as periodIndex,
                       period_name as periodName,
                       start_date as startDate,
                       end_date as endDate,
                       locked,
                       lock_reason as lockReason,
                       locked_at as lockedAt
                  from accounting_period
                 where fiscal_year_id = ?
                 order by period_index
            ''', [fiscalYearId]).collect { GroovyRowResult row ->
        mapPeriod(row)
      }
    }
  }

  AccountingPeriod findPeriod(long periodId) {
    databaseService.withSql { Sql sql ->
      findPeriod(sql, periodId)
    }
  }

  AccountingPeriod lockPeriod(long periodId, String reason) {
    String safeReason = reason?.trim()
    databaseService.withTransaction { Sql sql ->
      AccountingPeriod current = findPeriod(sql, periodId)
      if (current == null) {
        throw new IllegalArgumentException("Unknown accounting period id: ${periodId}")
      }
      if (current.locked) {
        throw new IllegalStateException("Accounting period ${current.periodName} is already locked.")
      }
      int updated = sql.executeUpdate('''
                update accounting_period
                   set locked = true,
                       lock_reason = ?,
                       locked_at = current_timestamp
                 where id = ?
            ''', [safeReason, periodId])
      if (updated != 1) {
        throw new IllegalStateException("Failed to lock accounting period id: ${periodId}")
      }
      AccountingPeriod lockedPeriod = findPeriod(sql, periodId)
      auditLogService.recordPeriodLocked(sql, lockedPeriod)
      lockedPeriod
    }
  }

  List<AccountingPeriod> lockPeriodsUpTo(long fiscalYearId, LocalDate endDate, String reason) {
    if (endDate == null) {
      throw new IllegalArgumentException('End date is required.')
    }
    String safeReason = reason?.trim()
    databaseService.withTransaction { Sql sql ->
      List<GroovyRowResult> rows = sql.rows('''
          select id
            from accounting_period
           where fiscal_year_id = ?
             and locked = false
             and end_date <= ?
           order by period_index
      ''', [fiscalYearId, Date.valueOf(endDate)]) as List<GroovyRowResult>
      List<AccountingPeriod> locked = []
      rows.each { GroovyRowResult row ->
        long periodId = ((Number) row.get('id')).longValue()
        sql.executeUpdate('''
            update accounting_period
               set locked = true,
                   lock_reason = ?,
                   locked_at = current_timestamp
             where id = ?
        ''', [safeReason, periodId])
        AccountingPeriod lockedPeriod = findPeriod(sql, periodId)
        auditLogService.recordPeriodLocked(sql, lockedPeriod)
        locked << lockedPeriod
      }
      locked
    }
  }

  boolean isDateLocked(long companyId, LocalDate accountingDate) {
    CompanyService.requireValidCompanyId(companyId)
    if (accountingDate == null) {
      throw new IllegalArgumentException('Accounting date is required.')
    }
    databaseService.withSql { Sql sql ->
      GroovyRowResult row = sql.firstRow('''
                select count(*) as total
                  from fiscal_year fy
                 where fy.company_id = ?
                   and fy.closed = true
                   and ? between fy.start_date and fy.end_date
            ''', [companyId, Date.valueOf(accountingDate)]) as GroovyRowResult
      ((Number) row.get('total')).intValue() > 0
    }
  }

  @PackageScope
  List<AccountingPeriod> createPeriods(Sql sql, long fiscalYearId, LocalDate startDate, LocalDate endDate) {
    List<AccountingPeriod> periods = []
    LocalDate cursor = startDate
    int index = 1
    while (!cursor.isAfter(endDate)) {
      LocalDate periodEnd = cursor.withDayOfMonth(cursor.lengthOfMonth())
      if (periodEnd.isAfter(endDate)) {
        periodEnd = endDate
      }
      String periodName = cursor.format(java.time.format.DateTimeFormatter.ofPattern('yyyy-MM'))
      sql.executeInsert('''
                insert into accounting_period (
                    fiscal_year_id,
                    period_index,
                    period_name,
                    start_date,
                    end_date,
                    locked,
                    lock_reason,
                    locked_at,
                    created_at
                ) values (?, ?, ?, ?, ?, false, null, null, current_timestamp)
            ''', [
          fiscalYearId,
          index,
          periodName,
          Date.valueOf(cursor),
          Date.valueOf(periodEnd)
      ])
      periods << new AccountingPeriod(null, fiscalYearId, index, periodName, cursor, periodEnd, false, null, null)
      cursor = periodEnd.plusDays(1)
      index++
    }
    periods
  }

  @PackageScope
  void lockUnlockedPeriods(Sql sql, long fiscalYearId, String reason) {
    sql.executeUpdate('''
            update accounting_period
               set locked = true,
                   lock_reason = coalesce(lock_reason, ?),
                   locked_at = coalesce(locked_at, current_timestamp)
             where fiscal_year_id = ?
               and locked = false
        ''', [reason?.trim(), fiscalYearId])
  }

  private static AccountingPeriod findPeriod(Sql sql, long periodId) {
    GroovyRowResult row = sql.firstRow('''
            select id,
                   fiscal_year_id as fiscalYearId,
                   period_index as periodIndex,
                   period_name as periodName,
                   start_date as startDate,
                   end_date as endDate,
                   locked,
                   lock_reason as lockReason,
                   locked_at as lockedAt
              from accounting_period
             where id = ?
        ''', [periodId]) as GroovyRowResult
    row == null ? null : mapPeriod(row)
  }

  private static AccountingPeriod mapPeriod(GroovyRowResult row) {
    new AccountingPeriod(
        Long.valueOf(row.get('id').toString()),
        Long.valueOf(row.get('fiscalYearId').toString()),
        ((Number) row.get('periodIndex')).intValue(),
        row.get('periodName') as String,
        SqlValueMapper.toLocalDate(row.get('startDate')),
        SqlValueMapper.toLocalDate(row.get('endDate')),
        Boolean.TRUE == row.get('locked'),
        row.get('lockReason') as String,
        SqlValueMapper.toLocalDateTime(row.get('lockedAt'))
    )
  }
}
