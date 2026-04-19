package se.alipsa.accounting.service

import groovy.sql.GroovyRowResult
import groovy.sql.Sql

import se.alipsa.accounting.domain.Voucher
import se.alipsa.accounting.domain.VoucherLine
import se.alipsa.accounting.domain.VoucherSeries
import se.alipsa.accounting.domain.VoucherStatus

import java.math.RoundingMode
import java.sql.Date
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Handles safe voucher creation, correction and number allocation.
 */
final class VoucherService {

  private static final int AMOUNT_SCALE = 2
  private static final int DEFAULT_SEARCH_LIMIT = 500

  private final DatabaseService databaseService
  private final AuditLogService auditLogService

  VoucherService() {
    this(DatabaseService.instance)
  }

  VoucherService(DatabaseService databaseService) {
    this(databaseService, new AuditLogService(databaseService))
  }

  VoucherService(DatabaseService databaseService, AuditLogService auditLogService) {
    this.databaseService = databaseService
    this.auditLogService = auditLogService
  }

  VoucherSeries ensureSeries(long fiscalYearId, String seriesCode, String seriesName = null) {
    databaseService.withTransaction { Sql sql ->
      ensureSeries(sql, fiscalYearId, seriesCode, seriesName)
    }
  }

  List<VoucherSeries> listSeries(long fiscalYearId) {
    databaseService.withSql { Sql sql ->
      sql.rows('''
          select id,
                 fiscal_year_id as fiscalYearId,
                 series_code as seriesCode,
                 series_name as seriesName,
                 next_running_number as nextRunningNumber
            from voucher_series
           where fiscal_year_id = ?
           order by series_code
      ''', [fiscalYearId]).collect { GroovyRowResult row ->
        mapSeries(row)
      }
    }
  }

  Voucher createVoucher(
      long fiscalYearId,
      String seriesCode,
      LocalDate accountingDate,
      String description,
      List<VoucherLine> lines
  ) {
    databaseService.withTransaction { Sql sql ->
      insertVoucher(sql, fiscalYearId, seriesCode, accountingDate, description, lines, null)
    }
  }

  Voucher createVoucher(
      Sql sql,
      long fiscalYearId,
      String seriesCode,
      LocalDate accountingDate,
      String description,
      List<VoucherLine> lines
  ) {
    insertVoucher(sql, fiscalYearId, seriesCode, accountingDate, description, lines, null, false)
  }

  /**
   * Creates a voucher without checking the accounting period lock.
   * Used exclusively by ClosingService for year-end closing vouchers.
   */
  Voucher createVoucherBypassLock(
      Sql sql,
      long fiscalYearId,
      String seriesCode,
      LocalDate accountingDate,
      String description,
      List<VoucherLine> lines
  ) {
    insertVoucher(sql, fiscalYearId, seriesCode, accountingDate, description, lines, null, true)
  }

  Voucher updateVoucher(long voucherId, LocalDate accountingDate, String description, List<VoucherLine> lines) {
    databaseService.withTransaction { Sql sql ->
      Voucher current = requireVoucher(sql, voucherId)
      if (current.status != VoucherStatus.ACTIVE) {
        throw new IllegalStateException('Verifikationen kan inte ändras — den är inte aktiv.')
      }

      String safeDescription = validateVoucherEnvelope(sql, current.fiscalYearId, accountingDate, description)
      ensurePeriodUnlocked(sql, current.fiscalYearId, accountingDate)
      long companyId = resolveCompanyId(sql, current.fiscalYearId)
      List<VoucherLine> safeLines = normalizeLines(sql, companyId, lines, true)
      replaceLines(sql, voucherId, companyId, safeLines)
      sql.executeUpdate('''
          update voucher
             set accounting_date = ?,
                 description = ?,
                 updated_at = current_timestamp
           where id = ?
             and status = 'ACTIVE'
      ''', [Date.valueOf(accountingDate), safeDescription, voucherId])
      Voucher updatedVoucher = requireVoucher(sql, voucherId)
      auditLogService.recordVoucherUpdated(sql, updatedVoucher)
      updatedVoucher
    }
  }

  Voucher cancelVoucher(long voucherId) {
    databaseService.withTransaction { Sql sql ->
      Voucher current = requireVoucher(sql, voucherId)
      if (current.status != VoucherStatus.ACTIVE) {
        throw new IllegalStateException('Endast aktiva verifikationer kan makuleras.')
      }
      ensurePeriodUnlocked(sql, current.fiscalYearId, current.accountingDate)
      int updated = sql.executeUpdate('''
          update voucher
             set status = 'CANCELLED',
                 updated_at = current_timestamp
           where id = ?
             and status = 'ACTIVE'
      ''', [voucherId])
      if (updated != 1) {
        throw new IllegalStateException("Verifikationen kunde inte makuleras: ${voucherId}")
      }
      Voucher cancelledVoucher = requireVoucher(sql, voucherId)
      auditLogService.recordVoucherCancelled(sql, cancelledVoucher)
      cancelledVoucher
    }
  }

  boolean isLastInSeries(long voucherId) {
    databaseService.withSql { Sql sql ->
      Voucher voucher = requireVoucher(sql, voucherId)
      GroovyRowResult row = sql.firstRow('''
          select max(running_number) as maxRunning
            from voucher
           where voucher_series_id = ?
             and fiscal_year_id = ?
      ''', [voucher.voucherSeriesId, voucher.fiscalYearId]) as GroovyRowResult
      int maxRunning = row?.get('maxRunning') == null ? 0 : ((Number) row.get('maxRunning')).intValue()
      voucher.runningNumber != null && voucher.runningNumber == maxRunning
    }
  }

  void deleteVoucher(long voucherId) {
    databaseService.withTransaction { Sql sql ->
      Voucher voucher = requireVoucher(sql, voucherId)
      if (voucher.status != VoucherStatus.ACTIVE) {
        throw new IllegalStateException('Endast aktiva verifikationer kan tas bort.')
      }
      ensurePeriodUnlocked(sql, voucher.fiscalYearId, voucher.accountingDate)
      GroovyRowResult row = sql.firstRow('''
          select max(running_number) as maxRunning
            from voucher
           where voucher_series_id = ?
             and fiscal_year_id = ?
      ''', [voucher.voucherSeriesId, voucher.fiscalYearId]) as GroovyRowResult
      int maxRunning = row?.get('maxRunning') == null ? 0 : ((Number) row.get('maxRunning')).intValue()
      if (voucher.runningNumber == null || voucher.runningNumber != maxRunning) {
        throw new IllegalStateException('Endast den sista verifikationen i serien kan tas bort.')
      }
      sql.executeUpdate('delete from voucher_line where voucher_id = ?', [voucherId])
      sql.executeUpdate('delete from attachment where voucher_id = ?', [voucherId])
      sql.executeUpdate('delete from voucher where id = ?', [voucherId])
      sql.executeUpdate('''
          update voucher_series
             set next_running_number = ?,
                 updated_at = current_timestamp
           where id = ?
      ''', [voucher.runningNumber, voucher.voucherSeriesId])
    }
  }

  Voucher createCorrectionVoucher(long originalVoucherId, String description = null) {
    databaseService.withTransaction { Sql sql ->
      Voucher original = requireVoucher(sql, originalVoucherId)
      if (original.status != VoucherStatus.ACTIVE) {
        throw new IllegalStateException('Endast aktiva verifikationer kan korrigeras.')
      }

      String safeDescription = description?.trim()
      if (!safeDescription) {
        safeDescription = "Korrigering av ${original.voucherNumber ?: original.id}"
      }
      // A correction keeps the original accounting date and is allowed even when
      // that period has been locked — correction is the intended mechanism for
      // amending vouchers whose period is no longer editable.
      List<VoucherLine> reversingLines = original.lines.collect { VoucherLine line ->
        new VoucherLine(
            null,
            null,
            line.lineIndex,
            line.accountId,
            line.accountNumber,
            line.accountName,
            line.description,
            line.creditAmount,
            line.debitAmount
        )
      }
      Voucher draft = insertVoucher(
          sql,
          original.fiscalYearId,
          original.seriesCode,
          original.accountingDate,
          safeDescription,
          reversingLines,
          original.id,
          true
      )
      sql.executeUpdate('''
          update voucher
             set status = 'CORRECTION',
                 updated_at = current_timestamp
           where id = ?
      ''', [draft.id])
      Voucher correctionVoucher = requireVoucher(sql, draft.id)
      auditLogService.recordCorrectionVoucher(sql, correctionVoucher)
      correctionVoucher
    }
  }

  Voucher findVoucher(long voucherId) {
    databaseService.withSql { Sql sql ->
      findVoucher(sql, voucherId)
    }
  }

  List<Voucher> listVouchers(long companyId, Long fiscalYearId = null, VoucherStatus status = null, String queryText = null) {
    CompanyService.requireValidCompanyId(companyId)
    databaseService.withSql { Sql sql ->
      StringBuilder query = new StringBuilder('''
          select v.id,
                 v.fiscal_year_id as fiscalYearId,
                 v.voucher_series_id as voucherSeriesId,
                 s.series_code as seriesCode,
                 s.series_name as seriesName,
                 v.running_number as runningNumber,
                 v.voucher_number as voucherNumber,
                 v.accounting_date as accountingDate,
                 v.description,
                 v.status,
                 v.original_voucher_id as originalVoucherId
            from voucher v
            join voucher_series s on s.id = v.voucher_series_id
           where v.company_id = ?
      ''')
      List<Object> params = [companyId]

      if (fiscalYearId != null) {
        query.append(' and v.fiscal_year_id = ?')
        params << fiscalYearId
      }
      if (status != null) {
        query.append(' and v.status = ?')
        params << status.name()
      }
      String normalizedQuery = queryText?.trim()?.toLowerCase(Locale.ROOT)
      if (normalizedQuery) {
        query.append(' and (lower(v.description) like ? or lower(v.voucher_number) like ?)')
        String pattern = "%${normalizedQuery}%"
        params << pattern
        params << pattern
      }

      query.append('''
           order by v.accounting_date desc,
                    coalesce(v.running_number, 2147483647) desc,
                    v.id desc
           limit ?
      ''')
      params << DEFAULT_SEARCH_LIMIT

      sql.rows(query.toString(), params).collect { GroovyRowResult row ->
        Voucher voucher = mapVoucher(row)
        voucher.lines = loadLines(sql, voucher.id)
        voucher
      }
    }
  }

  int countVouchers(long companyId, long fiscalYearId) {
    CompanyService.requireValidCompanyId(companyId)
    databaseService.withSql { Sql sql ->
      requireFiscalYearBelongsToCompany(sql, companyId, fiscalYearId)
      GroovyRowResult row = sql.firstRow('''
          select count(*) as cnt
            from voucher
           where company_id = ?
             and fiscal_year_id = ?
             and status in ('ACTIVE', 'CORRECTION')
      ''', [companyId, fiscalYearId])
      ((Number) row.cnt).intValue()
    }
  }

  /**
   * Used by backup freshness checks, so the lookup intentionally spans all fiscal years for the company.
   */
  boolean hasVouchersCreatedAfter(long companyId, LocalDateTime since) {
    CompanyService.requireValidCompanyId(companyId)
    databaseService.withSql { Sql sql ->
      GroovyRowResult row = sql.firstRow('''
          select count(*) as cnt
            from voucher
           where company_id = ?
             and status in ('ACTIVE', 'CORRECTION')
             and created_at > ?
      ''', [companyId, Timestamp.valueOf(since)])
      ((Number) row.cnt).intValue() > 0
    }
  }

  private Voucher insertVoucher(
      Sql sql,
      long fiscalYearId,
      String seriesCode,
      LocalDate accountingDate,
      String description,
      List<VoucherLine> lines,
      Long originalVoucherId,
      boolean skipLockCheck = false
  ) {
    String safeDescription = validateVoucherEnvelope(sql, fiscalYearId, accountingDate, description)
    if (!skipLockCheck) {
      ensurePeriodUnlocked(sql, fiscalYearId, accountingDate)
    }
    boolean requireActiveAccounts = originalVoucherId == null
    long companyId = resolveCompanyId(sql, fiscalYearId)
    List<VoucherLine> safeLines = normalizeLines(sql, companyId, lines, requireActiveAccounts)
    ensureBalanced(safeLines)
    VoucherSeries series = ensureSeries(sql, fiscalYearId, seriesCode, null)
    int runningNumber = allocateRunningNumber(sql, series.id)
    String voucherNumber = "${series.seriesCode}-${runningNumber}"
    List<List<Object>> keys = sql.executeInsert('''
        insert into voucher (
            fiscal_year_id,
            voucher_series_id,
            running_number,
            voucher_number,
            accounting_date,
            description,
            status,
            original_voucher_id,
            company_id,
            created_at,
            updated_at
        ) values (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, current_timestamp, current_timestamp)
    ''', [
        fiscalYearId,
        series.id,
        runningNumber,
        voucherNumber,
        Date.valueOf(accountingDate),
        safeDescription,
        originalVoucherId,
        companyId
    ])
    long voucherId = ((Number) keys.first().first()).longValue()
    replaceLines(sql, voucherId, companyId, safeLines)
    Voucher created = requireVoucher(sql, voucherId)
    if (originalVoucherId == null) {
      auditLogService.recordVoucherCreated(sql, created)
    }
    created
  }

  private VoucherSeries ensureSeries(Sql sql, long fiscalYearId, String seriesCode, String seriesName) {
    requireFiscalYear(sql, fiscalYearId)
    String safeCode = normalizeSeriesCode(seriesCode)
    GroovyRowResult row = sql.firstRow('''
        select id,
               fiscal_year_id as fiscalYearId,
               series_code as seriesCode,
               series_name as seriesName,
               next_running_number as nextRunningNumber
          from voucher_series
         where fiscal_year_id = ?
           and series_code = ?
    ''', [fiscalYearId, safeCode]) as GroovyRowResult
    if (row != null) {
      return mapSeries(row)
    }

    String safeName = seriesName?.trim()
    if (!safeName) {
      safeName = "Serie ${safeCode}"
    }
    List<List<Object>> keys = sql.executeInsert('''
        insert into voucher_series (
            fiscal_year_id,
            series_code,
            series_name,
            next_running_number,
            created_at,
            updated_at
        ) values (?, ?, ?, 1, current_timestamp, current_timestamp)
    ''', [fiscalYearId, safeCode, safeName])
    long seriesId = ((Number) keys.first().first()).longValue()
    GroovyRowResult created = sql.firstRow('''
        select id,
               fiscal_year_id as fiscalYearId,
               series_code as seriesCode,
               series_name as seriesName,
               next_running_number as nextRunningNumber
          from voucher_series
         where id = ?
    ''', [seriesId]) as GroovyRowResult
    mapSeries(created)
  }

  private static String validateVoucherEnvelope(
      Sql sql,
      long fiscalYearId,
      LocalDate accountingDate,
      String description
  ) {
    requireFiscalYearDate(sql, fiscalYearId, accountingDate)
    String safeDescription = description?.trim()
    if (!safeDescription) {
      throw new IllegalArgumentException('Verifikationstext är obligatorisk.')
    }
    if (safeDescription.length() > 500) {
      throw new IllegalArgumentException('Verifikationstext får vara högst 500 tecken.')
    }
    safeDescription
  }

  private static void requireFiscalYear(Sql sql, long fiscalYearId) {
    GroovyRowResult row = sql.firstRow(
        'select count(*) as total from fiscal_year where id = ?',
        [fiscalYearId]
    ) as GroovyRowResult
    if (((Number) row.get('total')).intValue() != 1) {
      throw new IllegalArgumentException("Okänt räkenskapsår: ${fiscalYearId}")
    }
  }

  private static void requireFiscalYearBelongsToCompany(Sql sql, long companyId, long fiscalYearId) {
    long actualCompanyId = CompanyService.resolveFromFiscalYear(sql, fiscalYearId)
    if (actualCompanyId != companyId) {
      throw new IllegalArgumentException("Fiscal year ${fiscalYearId} does not belong to company ${companyId}.")
    }
  }

  private static void requireFiscalYearDate(Sql sql, long fiscalYearId, LocalDate accountingDate) {
    if (accountingDate == null) {
      throw new IllegalArgumentException('Bokföringsdatum är obligatoriskt.')
    }
    GroovyRowResult row = sql.firstRow('''
        select count(*) as total
          from fiscal_year
         where id = ?
           and ? between start_date and end_date
    ''', [fiscalYearId, Date.valueOf(accountingDate)]) as GroovyRowResult
    if (((Number) row.get('total')).intValue() != 1) {
      throw new IllegalArgumentException('Bokföringsdatum måste ligga inom valt räkenskapsår.')
    }
  }

  private static void ensurePeriodUnlocked(Sql sql, long fiscalYearId, LocalDate accountingDate) {
    GroovyRowResult row = sql.firstRow('''
        select locked
          from accounting_period
         where fiscal_year_id = ?
           and ? between start_date and end_date
    ''', [fiscalYearId, Date.valueOf(accountingDate)]) as GroovyRowResult
    if (row != null && Boolean.TRUE == row.get('locked')) {
      throw new LockedAccountingPeriodException('Perioden är låst och verifikationen kan inte ändras.')
    }
  }

  private static void ensureBalanced(List<VoucherLine> lines) {
    BigDecimal debitTotal = lines.sum(BigDecimal.ZERO) { VoucherLine line -> line.debitAmount ?: BigDecimal.ZERO } as BigDecimal
    BigDecimal creditTotal = lines.sum(BigDecimal.ZERO) { VoucherLine line -> line.creditAmount ?: BigDecimal.ZERO } as BigDecimal
    if (debitTotal != creditTotal) {
      throw new IllegalArgumentException(
          "Verifikationen är inte balanserad: debet=${debitTotal}, kredit=${creditTotal}")
    }
  }

  private static List<VoucherLine> normalizeLines(
      Sql sql,
      long companyId,
      List<VoucherLine> lines,
      boolean requireActiveAccounts
  ) {
    if (lines == null || lines.isEmpty()) {
      throw new IllegalArgumentException('Minst en verifikationsrad krävs.')
    }

    List<VoucherLine> safeLines = []
    lines.eachWithIndex { VoucherLine line, int index ->
      safeLines << normalizeLine(sql, companyId, line, index + 1, requireActiveAccounts)
    }
    safeLines
  }

  private static VoucherLine normalizeLine(
      Sql sql,
      long companyId,
      VoucherLine line,
      int lineIndex,
      boolean requireActiveAccount
  ) {
    if (line == null) {
      throw new IllegalArgumentException("Verifikationsrad ${lineIndex} saknas.")
    }
    String accountNumber = normalizeAccountNumber(line.accountNumber)
    GroovyRowResult account = sql.firstRow('''
        select id,
               account_number as accountNumber,
               account_name as accountName,
               active
          from account
         where company_id = ?
           and account_number = ?
    ''', [companyId, accountNumber]) as GroovyRowResult
    if (account == null) {
      throw new IllegalArgumentException("Okänt konto på rad ${lineIndex}: ${accountNumber}")
    }
    if (requireActiveAccount && Boolean.TRUE != account.get('active')) {
      throw new IllegalArgumentException("Kontot är inaktivt på rad ${lineIndex}: ${accountNumber}")
    }

    BigDecimal debit = normalizeAmount(line.debitAmount)
    BigDecimal credit = normalizeAmount(line.creditAmount)
    if (debit > BigDecimal.ZERO && credit > BigDecimal.ZERO) {
      throw new IllegalArgumentException("Rad ${lineIndex} får inte ha både debet och kredit.")
    }
    if (debit == BigDecimal.ZERO && credit == BigDecimal.ZERO) {
      throw new IllegalArgumentException("Rad ${lineIndex} måste ha debet eller kredit.")
    }

    new VoucherLine(
        null,
        line.voucherId,
        lineIndex,
        ((Number) account.get('id')).longValue(),
        accountNumber,
        account.get('accountName') as String,
        normalizeLineDescription(line.description),
        debit,
        credit
    )
  }

  private static String normalizeAccountNumber(String accountNumber) {
    String normalized = accountNumber?.trim()
    if (!(normalized ==~ /\d{4}/)) {
      throw new IllegalArgumentException('Kontonummer måste bestå av fyra siffror.')
    }
    normalized
  }

  private static String normalizeSeriesCode(String seriesCode) {
    String safeCode = seriesCode?.trim()?.toUpperCase(Locale.ROOT)
    if (!safeCode) {
      safeCode = 'A'
    }
    if (!(safeCode ==~ /[A-Z0-9]{1,8}/)) {
      throw new IllegalArgumentException('Verifikationsserie måste vara 1-8 tecken med A-Z eller 0-9.')
    }
    safeCode
  }

  private static String normalizeLineDescription(String description) {
    String safeDescription = description?.trim()
    if (safeDescription != null && safeDescription.length() > 500) {
      throw new IllegalArgumentException('Radtext får vara högst 500 tecken.')
    }
    safeDescription
  }

  private static BigDecimal normalizeAmount(BigDecimal amount) {
    BigDecimal safeAmount = (amount ?: BigDecimal.ZERO).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)
    if (safeAmount < BigDecimal.ZERO) {
      throw new IllegalArgumentException('Belopp får inte vara negativa.')
    }
    safeAmount
  }

  private static int allocateRunningNumber(Sql sql, long voucherSeriesId) {
    GroovyRowResult row = sql.firstRow('''
        select next_running_number as nextRunningNumber
          from voucher_series
         where id = ?
         for update
    ''', [voucherSeriesId]) as GroovyRowResult
    if (row == null) {
      throw new IllegalStateException("Verifikationsserien saknas: ${voucherSeriesId}")
    }
    int runningNumber = ((Number) row.get('nextRunningNumber')).intValue()
    int updated = sql.executeUpdate('''
        update voucher_series
           set next_running_number = ?,
               updated_at = current_timestamp
         where id = ?
    ''', [runningNumber + 1, voucherSeriesId])
    if (updated != 1) {
      throw new IllegalStateException("Verifikationsserien kunde inte uppdateras: ${voucherSeriesId}")
    }
    runningNumber
  }

  private static long resolveCompanyId(Sql sql, long fiscalYearId) {
    CompanyService.resolveFromFiscalYear(sql, fiscalYearId)
  }

  private static void replaceLines(Sql sql, long voucherId, long companyId, List<VoucherLine> lines) {
    sql.executeUpdate('delete from voucher_line where voucher_id = ?', [voucherId])
    lines.eachWithIndex { VoucherLine line, int index ->
      if (line.accountId == null) {
        throw new IllegalStateException("Verifikationsrad ${index + 1} saknar account_id för konto ${line.accountNumber}.")
      }
      sql.executeInsert('''
          insert into voucher_line (
              voucher_id,
              line_index,
              account_id,
              account_number,
              account_name,
              line_description,
              debit_amount,
              credit_amount,
              company_id,
              created_at
          ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
      ''', [
          voucherId,
          index + 1,
          line.accountId,
          line.accountNumber,
          line.accountName,
          line.description,
          line.debitAmount,
          line.creditAmount,
          companyId
      ])
    }
  }

  private Voucher requireVoucher(Sql sql, long voucherId) {
    Voucher voucher = findVoucher(sql, voucherId)
    if (voucher == null) {
      throw new IllegalArgumentException("Okänd verifikation: ${voucherId}")
    }
    voucher
  }

  private static Voucher findVoucher(Sql sql, long voucherId) {
    GroovyRowResult row = sql.firstRow('''
        select v.id,
               v.fiscal_year_id as fiscalYearId,
               v.voucher_series_id as voucherSeriesId,
               s.series_code as seriesCode,
               s.series_name as seriesName,
               v.running_number as runningNumber,
               v.voucher_number as voucherNumber,
               v.accounting_date as accountingDate,
               v.description,
               v.status,
               v.original_voucher_id as originalVoucherId
          from voucher v
          join voucher_series s on s.id = v.voucher_series_id
         where v.id = ?
    ''', [voucherId]) as GroovyRowResult
    if (row == null) {
      return null
    }
    Voucher voucher = mapVoucher(row)
    voucher.lines = loadLines(sql, voucher.id)
    voucher
  }

  private static List<VoucherLine> loadLines(Sql sql, Long voucherId) {
    sql.rows('''
        select vl.id,
               vl.voucher_id as voucherId,
               vl.line_index as lineIndex,
               vl.account_id as accountId,
               vl.account_number as accountNumber,
               vl.account_name as accountName,
               vl.line_description as lineDescription,
               vl.debit_amount as debitAmount,
               vl.credit_amount as creditAmount
          from voucher_line vl
         where vl.voucher_id = ?
         order by vl.line_index
    ''', [voucherId]).collect { GroovyRowResult row ->
      new VoucherLine(
          Long.valueOf(row.get('id').toString()),
          Long.valueOf(row.get('voucherId').toString()),
          ((Number) row.get('lineIndex')).intValue(),
          Long.valueOf(row.get('accountId').toString()),
          row.get('accountNumber') as String,
          row.get('accountName') as String,
          row.get('lineDescription') as String,
          new BigDecimal(row.get('debitAmount').toString()),
          new BigDecimal(row.get('creditAmount').toString())
      )
    }
  }

  private static Voucher mapVoucher(GroovyRowResult row) {
    new Voucher(
        Long.valueOf(row.get('id').toString()),
        Long.valueOf(row.get('fiscalYearId').toString()),
        Long.valueOf(row.get('voucherSeriesId').toString()),
        row.get('seriesCode') as String,
        row.get('seriesName') as String,
        row.get('runningNumber') == null ? null : Integer.valueOf(row.get('runningNumber').toString()),
        row.get('voucherNumber') as String,
        SqlValueMapper.toLocalDate(row.get('accountingDate')),
        row.get('description') as String,
        VoucherStatus.valueOf(row.get('status') as String),
        row.get('originalVoucherId') == null ? null : Long.valueOf(row.get('originalVoucherId').toString()),
        []
    )
  }

  private static VoucherSeries mapSeries(GroovyRowResult row) {
    new VoucherSeries(
        Long.valueOf(row.get('id').toString()),
        Long.valueOf(row.get('fiscalYearId').toString()),
        row.get('seriesCode') as String,
        row.get('seriesName') as String,
        ((Number) row.get('nextRunningNumber')).intValue()
    )
  }
}
