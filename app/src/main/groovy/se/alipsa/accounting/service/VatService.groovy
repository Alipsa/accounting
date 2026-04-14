package se.alipsa.accounting.service

import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.transform.Canonical
import groovy.transform.PackageScope

import se.alipsa.accounting.domain.VatCode
import se.alipsa.accounting.domain.VatPeriod
import se.alipsa.accounting.domain.VatPeriodicity
import se.alipsa.accounting.domain.Voucher
import se.alipsa.accounting.domain.VoucherLine

import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Date

/**
 * Calculates VAT reports, manages VAT periods and books VAT transfer vouchers.
 */
final class VatService {

  static final String OPEN = 'OPEN'
  static final String REPORTED = 'REPORTED'
  static final String LOCKED = 'LOCKED'

  private static final int AMOUNT_SCALE = 2
  private static final Set<String> VAT_BALANCE_ACCOUNT_CLASSES = ['ASSET', 'LIABILITY'] as Set<String>
  static final String DEFAULT_TRANSFER_SERIES = 'M'
  static final String DEFAULT_SETTLEMENT_ACCOUNT = '2650'

  private final DatabaseService databaseService
  private final VoucherService voucherService
  private final AuditLogService auditLogService

  VatService() {
    this(DatabaseService.instance)
  }

  VatService(DatabaseService databaseService) {
    this(databaseService, new VoucherService(databaseService), new AuditLogService(databaseService))
  }

  VatService(DatabaseService databaseService, VoucherService voucherService) {
    this(databaseService, voucherService, new AuditLogService(databaseService))
  }

  VatService(DatabaseService databaseService, VoucherService voucherService, AuditLogService auditLogService) {
    this.databaseService = databaseService
    this.voucherService = voucherService
    this.auditLogService = auditLogService
  }

  List<VatPeriod> listPeriods(long fiscalYearId) {
    databaseService.withTransaction { Sql sql ->
      ensurePeriodsForFiscalYear(sql, fiscalYearId)
      listPeriods(sql, fiscalYearId)
    }
  }

  VatPeriod findPeriod(long vatPeriodId) {
    databaseService.withSql { Sql sql ->
      findPeriod(sql, vatPeriodId)
    }
  }

  VatReport calculateReport(long vatPeriodId) {
    databaseService.withSql { Sql sql ->
      VatPeriod period = requirePeriod(sql, vatPeriodId)
      buildReport(sql, period)
    }
  }

  List<String> validateReport(long vatPeriodId) {
    databaseService.withSql { Sql sql ->
      VatPeriod period = requirePeriod(sql, vatPeriodId)
      if (period.status == OPEN) {
        return noProblems()
      }
      if (!period.reportHash) {
        return singleProblem("Momsperiod ${period.periodName} saknar sparad rapporthash.")
      }
      VatReport report = buildReport(sql, period)
      String currentHash = calculateReportHash(report)
      currentHash == period.reportHash
          ? noProblems()
          : singleProblem("Momsperiod ${period.periodName} har avvikande rapporthash.")
    }
  }

  VatPeriod reportPeriod(long vatPeriodId) {
    databaseService.withTransaction { Sql sql ->
      VatPeriod period = requirePeriod(sql, vatPeriodId)
      if (period.status == LOCKED) {
        throw new IllegalStateException("Momsperiod ${period.periodName} är redan låst.")
      }
      VatReport report = buildReport(sql, period)
      String reportHash = calculateReportHash(report)
      int updated = sql.executeUpdate('''
          update vat_period
             set status = ?,
                 report_hash = ?,
                 reported_at = current_timestamp,
                 updated_at = current_timestamp
           where id = ?
             and status = ?
      ''', [REPORTED, reportHash, vatPeriodId, OPEN])
      if (updated == 0 && period.status == REPORTED) {
        return requirePeriod(sql, vatPeriodId)
      }
      if (updated != 1) {
        throw new IllegalStateException("Momsperiod ${period.periodName} kunde inte rapporteras.")
      }
      VatPeriod reportedPeriod = requirePeriod(sql, vatPeriodId)
      auditLogService.recordVatPeriodReported(sql, reportedPeriod, reportHash)
      reportedPeriod
    }
  }

  Voucher bookTransfer(long vatPeriodId, String seriesCode = DEFAULT_TRANSFER_SERIES, String settlementAccount = DEFAULT_SETTLEMENT_ACCOUNT) {
    databaseService.withTransaction { Sql sql ->
      VatPeriod period = requirePeriod(sql, vatPeriodId)
      if (period.status == OPEN) {
        throw new IllegalStateException("Momsperiod ${period.periodName} måste rapporteras innan momsöverföring kan bokföras.")
      }
      if (period.status == LOCKED) {
        throw new IllegalStateException("Momsperiod ${period.periodName} är redan låst.")
      }

      List<TransferBalance> balances = loadTransferBalances(sql, period)
      if (balances.isEmpty()) {
        throw new IllegalStateException("Momsperiod ${period.periodName} saknar momsbalanser att överföra.")
      }
      long companyId = resolveCompanyId(sql, period.fiscalYearId)
      requireSettlementAccount(sql, companyId, settlementAccount)
      List<VoucherLine> lines = buildTransferLines(balances, settlementAccount)
      String description = "Momsöverföring ${period.periodName}"
      Voucher voucher = bookTransferVoucher(sql, period, seriesCode, description, lines)
      int updated = sql.executeUpdate('''
          update vat_period
             set status = ?,
                 locked_at = current_timestamp,
                 transfer_voucher_id = ?,
                 updated_at = current_timestamp
           where id = ?
             and status = ?
      ''', [LOCKED, voucher.id, vatPeriodId, REPORTED])
      if (updated != 1) {
        throw new IllegalStateException("Momsperiod ${period.periodName} kunde inte låsas efter momsöverföring.")
      }
      VatPeriod lockedPeriod = requirePeriod(sql, vatPeriodId)
      auditLogService.recordVatPeriodLocked(sql, lockedPeriod)
      voucher
    }
  }

  @PackageScope
  static void ensurePeriodsForFiscalYear(Sql sql, long fiscalYearId) {
    GroovyRowResult fiscalYearRow = sql.firstRow(
        'select company_id as companyId from fiscal_year where id = ?',
        [fiscalYearId]
    ) as GroovyRowResult
    if (fiscalYearRow == null) {
      throw new IllegalArgumentException("Okänt räkenskapsår: ${fiscalYearId}")
    }
    long companyId = ((Number) fiscalYearRow.get('companyId')).longValue()

    GroovyRowResult existing = sql.firstRow(
        'select count(*) as total from vat_period where fiscal_year_id = ?',
        [fiscalYearId]
    ) as GroovyRowResult
    if (((Number) existing.get('total')).intValue() > 0) {
      return
    }

    VatPeriodicity periodicity = loadPeriodicity(sql, companyId)
    List<GroovyRowResult> accountingPeriods = sql.rows('''
        select period_index as periodIndex,
               period_name as periodName,
               start_date as startDate,
               end_date as endDate
          from accounting_period
         where fiscal_year_id = ?
         order by period_index
    ''', [fiscalYearId]) as List<GroovyRowResult>
    if (accountingPeriods.isEmpty()) {
      return
    }

    if (periodicity == VatPeriodicity.ANNUAL) {
      GroovyRowResult first = accountingPeriods.first()
      GroovyRowResult last = accountingPeriods.last()
      sql.executeInsert('''
          insert into vat_period (
              fiscal_year_id,
              period_index,
              period_name,
              start_date,
              end_date,
              status,
              report_hash,
              reported_at,
              locked_at,
              transfer_voucher_id,
              created_at,
              updated_at
          ) values (?, ?, ?, ?, ?, ?, null, null, null, null, current_timestamp, current_timestamp)
      ''', [
          fiscalYearId,
          1,
          "${SqlValueMapper.toLocalDate(first.get('startDate'))} - ${SqlValueMapper.toLocalDate(last.get('endDate'))}".toString(),
          first.get('startDate'),
          last.get('endDate'),
          OPEN
      ])
      return
    }

    accountingPeriods.each { GroovyRowResult row ->
      sql.executeInsert('''
          insert into vat_period (
              fiscal_year_id,
              period_index,
              period_name,
              start_date,
              end_date,
              status,
              report_hash,
              reported_at,
              locked_at,
              transfer_voucher_id,
              created_at,
              updated_at
          ) values (?, ?, ?, ?, ?, ?, null, null, null, null, current_timestamp, current_timestamp)
      ''', [
          fiscalYearId,
          ((Number) row.get('periodIndex')).intValue(),
          row.get('periodName') as String,
          row.get('startDate'),
          row.get('endDate'),
          OPEN
      ])
    }
  }

  private static VatPeriodicity loadPeriodicity(Sql sql, long companyId) {
    GroovyRowResult row = sql.firstRow('''
        select vat_periodicity as vatPeriodicity
          from company
         where id = ?
    ''', [companyId]) as GroovyRowResult
    VatPeriodicity.fromDatabaseValue(row?.get('vatPeriodicity') as String)
  }

  private static List<VatPeriod> listPeriods(Sql sql, long fiscalYearId) {
    sql.rows('''
        select id,
               fiscal_year_id as fiscalYearId,
               period_index as periodIndex,
               period_name as periodName,
               start_date as startDate,
               end_date as endDate,
               status,
               report_hash as reportHash,
               reported_at as reportedAt,
               locked_at as lockedAt,
               transfer_voucher_id as transferVoucherId
          from vat_period
         where fiscal_year_id = ?
         order by period_index
    ''', [fiscalYearId]).collect { GroovyRowResult row ->
      mapPeriod(row)
    }
  }

  private VatReport buildReport(Sql sql, VatPeriod period) {
    Map<VatCode, VatBucket> buckets = [:]
    VatReportSupport.loadSeeds(
        sql,
        period.fiscalYearId,
        period.startDate,
        period.endDate,
        true
    ).each { VatReportSupport.VatSeed seed ->
      VatBucket bucket = buckets.computeIfAbsent(seed.vatCode) { VatCode ignored ->
        new VatBucket()
      }
      bucket.baseAmount = scale(bucket.baseAmount + seed.baseAmount)
      bucket.postedOutputVat = scale(bucket.postedOutputVat + seed.postedOutputVat)
      bucket.postedInputVat = scale(bucket.postedInputVat + seed.postedInputVat)
      bucket.outputPostingCount += seed.outputPostingCount
      bucket.inputPostingCount += seed.inputPostingCount
    }

    List<VatReportRow> rows = buckets.entrySet()
        .sort { Map.Entry<VatCode, VatBucket> entry -> entry.key.ordinal() }
        .collect { Map.Entry<VatCode, VatBucket> entry ->
          VatCode vatCode = entry.key
          VatBucket bucket = entry.value
          BigDecimal computedOutputVat = scale(bucket.baseAmount * vatCode.outputRate)
          BigDecimal computedInputVat = scale(bucket.baseAmount * vatCode.inputRate)
          BigDecimal outputVat = bucket.outputPostingCount > 0 ? bucket.postedOutputVat : computedOutputVat
          BigDecimal inputVat = bucket.inputPostingCount > 0 ? bucket.postedInputVat : computedInputVat
          new VatReportRow(vatCode, vatCode.displayName, bucket.baseAmount, outputVat, inputVat)
        }

    BigDecimal outputVatTotal = rows.sum(BigDecimal.ZERO) { VatReportRow row -> row.outputVatAmount } as BigDecimal
    BigDecimal inputVatTotal = rows.sum(BigDecimal.ZERO) { VatReportRow row -> row.inputVatAmount } as BigDecimal
    new VatReport(period, rows, scale(outputVatTotal), scale(inputVatTotal), scale(outputVatTotal - inputVatTotal))
  }

  private static List<TransferBalance> loadTransferBalances(Sql sql, VatPeriod period) {
    sql.rows('''
        select a.id as accountId,
               vl.account_number as accountNumber,
               a.account_name as accountName,
               a.account_class as accountClass,
               a.normal_balance_side as normalBalanceSide,
               sum(vl.debit_amount) as debitAmount,
               sum(vl.credit_amount) as creditAmount
          from voucher v
          join voucher_line vl on vl.voucher_id = v.id
          join account a on a.id = vl.account_id
         where v.fiscal_year_id = ?
           and v.status in ('BOOKED', 'CORRECTION')
           and v.accounting_date between ? and ?
           and a.vat_code is not null
           and a.account_class in ('ASSET', 'LIABILITY')
         group by vl.account_number, a.account_name, a.account_class, a.normal_balance_side
         having sum(vl.debit_amount) <> sum(vl.credit_amount)
         order by vl.account_number
    ''', [
        period.fiscalYearId,
        Date.valueOf(period.startDate),
        Date.valueOf(period.endDate)
    ]).collect { GroovyRowResult row ->
      BigDecimal balance = signedAmount(
          new BigDecimal(row.get('debitAmount').toString()),
          new BigDecimal(row.get('creditAmount').toString()),
          row.get('normalBalanceSide') as String
      )
      Long accountId = ((Number) row.get('accountId')).longValue()
      new TransferBalance(
          accountId,
          row.get('accountNumber') as String,
          row.get('accountName') as String,
          row.get('normalBalanceSide') as String,
          balance
      )
    }.findAll { TransferBalance balance ->
      balance.amount != BigDecimal.ZERO
    }
  }

  private static List<VoucherLine> buildTransferLines(List<TransferBalance> balances, String settlementAccount) {
    List<VoucherLine> lines = []
    BigDecimal totalDebit = BigDecimal.ZERO
    BigDecimal totalCredit = BigDecimal.ZERO

    balances.eachWithIndex { TransferBalance balance, int index ->
      BigDecimal debit = BigDecimal.ZERO
      BigDecimal credit = BigDecimal.ZERO
      if (balance.normalBalanceSide == 'DEBIT') {
        debit = balance.amount < BigDecimal.ZERO ? scale(balance.amount.abs()) : BigDecimal.ZERO
        credit = balance.amount > BigDecimal.ZERO ? scale(balance.amount) : BigDecimal.ZERO
      } else {
        debit = balance.amount > BigDecimal.ZERO ? scale(balance.amount) : BigDecimal.ZERO
        credit = balance.amount < BigDecimal.ZERO ? scale(balance.amount.abs()) : BigDecimal.ZERO
      }
      totalDebit = scale(totalDebit + debit)
      totalCredit = scale(totalCredit + credit)
      lines << new VoucherLine(
          null,
          null,
          index + 1,
          balance.accountId,
          balance.accountNumber,
          balance.accountName,
          "Momsöverföring ${balance.accountNumber}",
          debit,
          credit
      )
    }

    BigDecimal delta = scale(totalDebit - totalCredit)
    if (delta != BigDecimal.ZERO) {
      BigDecimal settlementDebit = delta < BigDecimal.ZERO ? delta.abs() : BigDecimal.ZERO
      BigDecimal settlementCredit = delta > BigDecimal.ZERO ? delta : BigDecimal.ZERO
      lines << new VoucherLine(
          null,
          null,
          lines.size() + 1,
          null,
          settlementAccount,
          null,
          'Momsredovisning',
          scale(settlementDebit),
          scale(settlementCredit)
      )
    }
    lines
  }

  private static void requireSettlementAccount(Sql sql, long companyId, String accountNumber) {
    GroovyRowResult row = sql.firstRow('''
        select account_number as accountNumber,
               account_class as accountClass
          from account
         where company_id = ?
           and account_number = ?
    ''', [companyId, accountNumber?.trim()]) as GroovyRowResult
    if (row == null) {
      throw new IllegalArgumentException("Momsredovisningskonto saknas: ${accountNumber}")
    }
    if (!(row.get('accountClass') as String in VAT_BALANCE_ACCOUNT_CLASSES)) {
      throw new IllegalArgumentException("Momsredovisningskonto måste vara ett balanskonto: ${accountNumber}")
    }
  }

  private static BigDecimal signedAmount(BigDecimal debitAmount, BigDecimal creditAmount, String normalBalanceSide) {
    String safeNormalBalanceSide = normalBalanceSide?.trim()?.toUpperCase(Locale.ROOT)
    if (!safeNormalBalanceSide) {
      throw new IllegalStateException('Kontot saknar normal balanssida för momsberäkning.')
    }
    safeNormalBalanceSide == 'DEBIT'
        ? scale(debitAmount - creditAmount)
        : scale(creditAmount - debitAmount)
  }

  private static BigDecimal scale(BigDecimal amount) {
    (amount ?: BigDecimal.ZERO).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)
  }

  private static List<String> noProblems() {
    []
  }

  private static List<String> singleProblem(String message) {
    [message]
  }

  private static String calculateReportHash(VatReport report) {
    StringBuilder payload = new StringBuilder()
    payload.append(report.period.id).append('|')
    payload.append(REPORTED).append('|')
    payload.append(report.period.startDate).append('|')
    payload.append(report.period.endDate).append('\n')
    report.rows.each { VatReportRow row ->
      payload.append(row.vatCode.name()).append('|')
      payload.append(row.baseAmount.toPlainString()).append('|')
      payload.append(row.outputVatAmount.toPlainString()).append('|')
      payload.append(row.inputVatAmount.toPlainString()).append('\n')
    }
    payload.append(report.outputVatTotal.toPlainString()).append('|')
    payload.append(report.inputVatTotal.toPlainString()).append('|')
    payload.append(report.netVatToPay.toPlainString())
    MessageDigest digest = MessageDigest.getInstance('SHA-256')
    HexFormat.of().formatHex(digest.digest(payload.toString().getBytes(StandardCharsets.UTF_8)))
  }

  private static long resolveCompanyId(Sql sql, long fiscalYearId) {
    CompanyService.resolveFromFiscalYear(sql, fiscalYearId)
  }

  private static VatPeriod requirePeriod(Sql sql, long vatPeriodId) {
    VatPeriod period = findPeriod(sql, vatPeriodId)
    if (period == null) {
      throw new IllegalArgumentException("Okänd momsperiod: ${vatPeriodId}")
    }
    period
  }

  private Voucher bookTransferVoucher(
      Sql sql,
      VatPeriod period,
      String seriesCode,
      String description,
      List<VoucherLine> lines
  ) {
    try {
      return voucherService.createAndBook(sql, period.fiscalYearId, seriesCode, period.endDate, description, lines, true)
    } catch (LockedAccountingPeriodException exception) {
      throw new IllegalStateException('Momsöverföringen kan inte bokföras eftersom redovisningsperioden är låst.', exception)
    }
  }

  private static VatPeriod findPeriod(Sql sql, long vatPeriodId) {
    GroovyRowResult row = sql.firstRow('''
        select id,
               fiscal_year_id as fiscalYearId,
               period_index as periodIndex,
               period_name as periodName,
               start_date as startDate,
               end_date as endDate,
               status,
               report_hash as reportHash,
               reported_at as reportedAt,
               locked_at as lockedAt,
               transfer_voucher_id as transferVoucherId
          from vat_period
         where id = ?
    ''', [vatPeriodId]) as GroovyRowResult
    row == null ? null : mapPeriod(row)
  }

  private static VatPeriod mapPeriod(GroovyRowResult row) {
    new VatPeriod(
        Long.valueOf(row.get('id').toString()),
        Long.valueOf(row.get('fiscalYearId').toString()),
        ((Number) row.get('periodIndex')).intValue(),
        row.get('periodName') as String,
        SqlValueMapper.toLocalDate(row.get('startDate')),
        SqlValueMapper.toLocalDate(row.get('endDate')),
        row.get('status') as String,
        row.get('reportHash') as String,
        SqlValueMapper.toLocalDateTime(row.get('reportedAt')),
        SqlValueMapper.toLocalDateTime(row.get('lockedAt')),
        row.get('transferVoucherId') == null ? null : Long.valueOf(row.get('transferVoucherId').toString())
    )
  }

  @Canonical
  static final class VatReport {

    VatPeriod period
    List<VatReportRow> rows
    BigDecimal outputVatTotal
    BigDecimal inputVatTotal
    BigDecimal netVatToPay
  }

  @Canonical
  static final class VatReportRow {

    VatCode vatCode
    String label
    BigDecimal baseAmount
    BigDecimal outputVatAmount
    BigDecimal inputVatAmount
  }

  @Canonical
  private static final class VatBucket {

    BigDecimal baseAmount = BigDecimal.ZERO
    BigDecimal postedOutputVat = BigDecimal.ZERO
    BigDecimal postedInputVat = BigDecimal.ZERO
    int outputPostingCount
    int inputPostingCount
  }

  @Canonical
  private static final class TransferBalance {

    Long accountId
    String accountNumber
    String accountName
    String normalBalanceSide
    BigDecimal amount
  }
}
