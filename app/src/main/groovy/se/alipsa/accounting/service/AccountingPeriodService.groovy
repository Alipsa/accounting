package se.alipsa.accounting.service

import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.transform.CompileStatic
import groovy.transform.PackageScope

import se.alipsa.accounting.domain.AccountingPeriod

import java.sql.Date
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Handles period creation, lock status and date based lock checks.
 */
@CompileStatic
final class AccountingPeriodService {

    private final DatabaseService databaseService

    AccountingPeriodService(DatabaseService databaseService = DatabaseService.instance) {
        this.databaseService = databaseService
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
            int updated = sql.executeUpdate('''
                update accounting_period
                   set locked = true,
                       lock_reason = ?,
                       locked_at = current_timestamp
                 where id = ?
            ''', [safeReason, periodId])
            if (updated != 1) {
                throw new IllegalArgumentException("Unknown accounting period id: ${periodId}")
            }
            findPeriod(sql, periodId)
        }
    }

    boolean isDateLocked(LocalDate accountingDate) {
        if (accountingDate == null) {
            throw new IllegalArgumentException('Accounting date is required.')
        }
        databaseService.withSql { Sql sql ->
            GroovyRowResult row = sql.firstRow('''
                select count(*) as total
                  from accounting_period
                 where locked = true
                   and ? between start_date and end_date
            ''', [Date.valueOf(accountingDate)]) as GroovyRowResult
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
            toLocalDate(row.get('startDate')),
            toLocalDate(row.get('endDate')),
            Boolean.TRUE == row.get('locked'),
            row.get('lockReason') as String,
            toLocalDateTime(row.get('lockedAt'))
        )
    }

    private static LocalDate toLocalDate(Object value) {
        if (value == null) {
            return null
        }
        if (value instanceof LocalDate) {
            return (LocalDate) value
        }
        if (value instanceof Date) {
            return ((Date) value).toLocalDate()
        }
        throw new IllegalStateException("Unsupported date value: ${value.class.name}")
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        if (value == null) {
            return null
        }
        if (value instanceof LocalDateTime) {
            return (LocalDateTime) value
        }
        if (value instanceof Timestamp) {
            return ((Timestamp) value).toLocalDateTime()
        }
        throw new IllegalStateException("Unsupported timestamp value: ${value.class.name}")
    }
}
