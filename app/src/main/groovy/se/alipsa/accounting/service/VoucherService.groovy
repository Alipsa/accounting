package se.alipsa.accounting.service

import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.transform.PackageScope

import se.alipsa.accounting.domain.Voucher
import se.alipsa.accounting.domain.VoucherLine
import se.alipsa.accounting.domain.VoucherSeries
import se.alipsa.accounting.domain.VoucherStatus

import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Date
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Handles safe voucher creation, booking, correction and number allocation.
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

  Voucher createDraft(
      long fiscalYearId,
      String seriesCode,
      LocalDate accountingDate,
      String description,
      List<VoucherLine> lines
  ) {
    databaseService.withTransaction { Sql sql ->
      insertDraft(sql, fiscalYearId, seriesCode, accountingDate, description, lines, null)
    }
  }

  Voucher createAndBook(
      long fiscalYearId,
      String seriesCode,
      LocalDate accountingDate,
      String description,
      List<VoucherLine> lines
  ) {
    databaseService.withTransaction { Sql sql ->
      createAndBook(sql, fiscalYearId, seriesCode, accountingDate, description, lines, false)
    }
  }

  @PackageScope
  Voucher createAndBook(
      Sql sql,
      long fiscalYearId,
      String seriesCode,
      LocalDate accountingDate,
      String description,
      List<VoucherLine> lines
  ) {
    createAndBook(sql, fiscalYearId, seriesCode, accountingDate, description, lines, PostingPermissions.DEFAULT)
  }

  @PackageScope
  Voucher createAndBook(
      Sql sql,
      long fiscalYearId,
      String seriesCode,
      LocalDate accountingDate,
      String description,
      List<VoucherLine> lines,
      boolean allowReportedVatPeriod
  ) {
    createAndBook(
        sql,
        fiscalYearId,
        seriesCode,
        accountingDate,
        description,
        lines,
        new PostingPermissions(allowReportedVatPeriod, false)
    )
  }

  @PackageScope
  Voucher createAndBook(
      Sql sql,
      long fiscalYearId,
      String seriesCode,
      LocalDate accountingDate,
      String description,
      List<VoucherLine> lines,
      PostingPermissions postingPermissions
  ) {
    PostingPermissions safePermissions = postingPermissions ?: PostingPermissions.DEFAULT
    Voucher draft = insertDraft(sql, fiscalYearId, seriesCode, accountingDate, description, lines, null)
    bookVoucher(
        sql,
        draft.id,
        VoucherStatus.BOOKED,
        safePermissions.allowReportedVatPeriod,
        safePermissions.allowLockedPeriod
    )
  }

  Voucher updateDraft(long voucherId, LocalDate accountingDate, String description, List<VoucherLine> lines) {
    databaseService.withTransaction { Sql sql ->
      Voucher current = requireVoucher(sql, voucherId)
      if (current.status != VoucherStatus.DRAFT) {
        throw new IllegalStateException('Bokförda verifikationer kan inte ändras direkt. Skapa en korrigering istället.')
      }

      String safeDescription = validateVoucherEnvelope(sql, current.fiscalYearId, accountingDate, description)
      List<VoucherLine> safeLines = normalizeLines(sql, lines, false, true)
      replaceLines(sql, voucherId, safeLines)
      sql.executeUpdate('''
          update voucher
             set accounting_date = ?,
                 description = ?,
                 updated_at = current_timestamp
           where id = ?
             and status = 'DRAFT'
      ''', [Date.valueOf(accountingDate), safeDescription, voucherId])
      requireVoucher(sql, voucherId)
    }
  }

  Voucher bookDraft(long voucherId) {
    databaseService.withTransaction { Sql sql ->
      bookVoucher(sql, voucherId, VoucherStatus.BOOKED, false, false)
    }
  }

  Voucher cancelDraft(long voucherId) {
    databaseService.withTransaction { Sql sql ->
      Voucher current = requireVoucher(sql, voucherId)
      if (current.status != VoucherStatus.DRAFT) {
        throw new IllegalStateException('Endast utkast kan makuleras direkt. Bokförda verifikationer korrigeras.')
      }
      int updated = sql.executeUpdate('''
          update voucher
             set status = 'CANCELLED',
                 updated_at = current_timestamp
           where id = ?
             and status = 'DRAFT'
      ''', [voucherId])
      if (updated != 1) {
        throw new IllegalStateException("Verifikationen kunde inte makuleras: ${voucherId}")
      }
      Voucher cancelledVoucher = requireVoucher(sql, voucherId)
      auditLogService.recordVoucherCancelled(sql, cancelledVoucher)
      cancelledVoucher
    }
  }

  Voucher createCorrectionVoucher(long originalVoucherId, String description = null) {
    databaseService.withTransaction { Sql sql ->
      Voucher original = requireVoucher(sql, originalVoucherId)
      if (original.status != VoucherStatus.BOOKED) {
        throw new IllegalStateException('Endast bokförda verifikationer kan korrigeras.')
      }

      String safeDescription = description?.trim()
      if (!safeDescription) {
        safeDescription = "Korrigering av ${original.voucherNumber ?: original.id}"
      }
      // Conservative policy: the correction keeps the original accounting date.
      // If that period has been locked after booking, the correction is blocked and
      // the user must unlock the period before retrying.
      List<VoucherLine> reversingLines = original.lines.collect { VoucherLine line ->
        new VoucherLine(
            null,
            null,
            line.lineIndex,
            line.accountNumber,
            line.accountName,
            line.description,
            line.creditAmount,
            line.debitAmount
        )
      }
      Voucher draft = insertDraft(
          sql,
          original.fiscalYearId,
          original.seriesCode,
          original.accountingDate,
          safeDescription,
          reversingLines,
          original.id
      )
      bookVoucher(sql, draft.id, VoucherStatus.CORRECTION, false, false)
    }
  }

  Voucher findVoucher(long voucherId) {
    databaseService.withSql { Sql sql ->
      findVoucher(sql, voucherId)
    }
  }

  List<String> validateIntegrity() {
    databaseService.withTransaction { Sql sql ->
      List<String> problems = []
      sql.rows('select distinct company_id as companyId from voucher').each { GroovyRowResult companyRow ->
        long companyId = ((Number) companyRow.get('companyId')).longValue()
        problems.addAll(validateIntegrityForCompany(sql, companyId))
      }
      problems
    }
  }

  private static List<String> validateIntegrityForCompany(Sql sql, long companyId) {
    List<String> problems = []
    String expectedPreviousHash = null
    String actualLastHash = null
    sql.rows('''
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
               v.original_voucher_id as originalVoucherId,
               v.previous_hash as previousHash,
               v.content_hash as contentHash,
               v.booked_at as bookedAt
          from voucher v
          join voucher_series s on s.id = v.voucher_series_id
         where v.status in ('BOOKED', 'CORRECTION')
           and v.company_id = ?
         order by v.id
    ''', [companyId]).each { GroovyRowResult row ->
      Voucher voucher = mapVoucher(row)
      voucher.lines = loadLines(sql, voucher.id)
      if (voucher.previousHash != expectedPreviousHash) {
        problems << ("Verifikation ${voucher.id} har fel föregående hash." as String)
      }
      VoucherStatus status = VoucherStatus.valueOf(row.get('status') as String)
      String calculated = calculateContentHash(
          voucher,
          voucher.lines,
          status,
          voucher.runningNumber,
          voucher.voucherNumber,
          voucher.previousHash
      )
      if (voucher.contentHash != calculated) {
        problems << ("Verifikation ${voucher.id} har ogiltig innehållshash." as String)
      }
      expectedPreviousHash = voucher.contentHash
      actualLastHash = voucher.contentHash
    }
    ChainHead chainHead = lockChainHead(sql, companyId)
    if (chainHead == null) {
      problems << 'Verifikationskedjans huvud saknas.'
    } else if (chainHead.lastContentHash != actualLastHash) {
      problems << 'Verifikationskedjans huvud pekar inte på sista bokförda verifikation.'
    }
    problems
  }

  List<Voucher> listVouchers(Long fiscalYearId = null, VoucherStatus status = null, String queryText = null) {
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
                 v.original_voucher_id as originalVoucherId,
                 v.previous_hash as previousHash,
                 v.content_hash as contentHash,
                 v.booked_at as bookedAt
            from voucher v
            join voucher_series s on s.id = v.voucher_series_id
           where 1 = 1
      ''')
      List<Object> params = []

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

  private Voucher insertDraft(
      Sql sql,
      long fiscalYearId,
      String seriesCode,
      LocalDate accountingDate,
      String description,
      List<VoucherLine> lines,
      Long originalVoucherId
  ) {
    String safeDescription = validateVoucherEnvelope(sql, fiscalYearId, accountingDate, description)
    boolean requireActiveAccounts = originalVoucherId == null
    List<VoucherLine> safeLines = normalizeLines(sql, lines, false, requireActiveAccounts)
    VoucherSeries series = ensureSeries(sql, fiscalYearId, seriesCode, null)
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
            previous_hash,
            content_hash,
            booked_at,
            created_at,
            updated_at
        ) values (?, ?, null, null, ?, ?, 'DRAFT', ?, null, null, null, current_timestamp, current_timestamp)
    ''', [
        fiscalYearId,
        series.id,
        Date.valueOf(accountingDate),
        safeDescription,
        originalVoucherId
    ])
    long voucherId = ((Number) keys.first().first()).longValue()
    replaceLines(sql, voucherId, safeLines)
    Voucher draft = requireVoucher(sql, voucherId)
    if (originalVoucherId == null) {
      auditLogService.recordVoucherCreated(sql, draft)
    }
    draft
  }

  private Voucher bookVoucher(
      Sql sql,
      long voucherId,
      VoucherStatus targetStatus,
      boolean allowReportedVatPeriod,
      boolean allowLockedPeriod
  ) {
    Voucher current = requireVoucher(sql, voucherId)
    if (current.status != VoucherStatus.DRAFT) {
      throw new IllegalStateException('Endast utkast kan bokföras.')
    }
    if (!(targetStatus in [VoucherStatus.BOOKED, VoucherStatus.CORRECTION])) {
      throw new IllegalArgumentException("Ogiltig bokföringsstatus: ${targetStatus}")
    }

    validateVoucherEnvelope(sql, current.fiscalYearId, current.accountingDate, current.description)
    List<VoucherLine> safeLines = normalizeLines(sql, current.lines, true, targetStatus == VoucherStatus.BOOKED)
    ensurePostingAllowed(sql, current.fiscalYearId, current.accountingDate, targetStatus, allowReportedVatPeriod, allowLockedPeriod)

    int runningNumber = allocateRunningNumber(sql, current.voucherSeriesId)
    String voucherNumber = "${current.seriesCode}-${runningNumber}"
    long companyId = resolveCompanyId(sql, current.fiscalYearId)
    ChainHead chainHead = lockChainHead(sql, companyId)
    String previousHash = chainHead.lastContentHash
    LocalDateTime bookedAt = currentDatabaseTimestamp(sql)
    String contentHash = calculateContentHash(current, safeLines, targetStatus, runningNumber, voucherNumber, previousHash)

    int updated = sql.executeUpdate('''
        update voucher
           set status = ?,
               running_number = ?,
               voucher_number = ?,
               previous_hash = ?,
               content_hash = ?,
               booked_at = ?,
               updated_at = current_timestamp
         where id = ?
           and status = 'DRAFT'
    ''', [
        targetStatus.name(),
        runningNumber,
        voucherNumber,
        previousHash,
        contentHash,
        Timestamp.valueOf(bookedAt),
        voucherId
    ])
    if (updated != 1) {
      throw new IllegalStateException("Verifikationen kunde inte bokföras: ${voucherId}")
    }
    updateChainHead(sql, companyId, contentHash)
    Voucher bookedVoucher = requireVoucher(sql, voucherId)
    if (targetStatus == VoucherStatus.CORRECTION) {
      auditLogService.recordCorrectionVoucher(sql, bookedVoucher)
    } else {
      auditLogService.recordVoucherBooked(sql, bookedVoucher)
    }
    bookedVoucher
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

  private static List<VoucherLine> normalizeLines(
      Sql sql,
      List<VoucherLine> lines,
      boolean requireBalanced,
      boolean requireActiveAccounts
  ) {
    if (lines == null || lines.isEmpty()) {
      throw new IllegalArgumentException('Minst en verifikationsrad krävs.')
    }

    List<VoucherLine> safeLines = []
    lines.eachWithIndex { VoucherLine line, int index ->
      safeLines << normalizeLine(sql, line, index + 1, requireActiveAccounts)
    }

    if (requireBalanced) {
      validateBalanced(safeLines)
    }
    safeLines
  }

  private static VoucherLine normalizeLine(
      Sql sql,
      VoucherLine line,
      int lineIndex,
      boolean requireActiveAccount
  ) {
    if (line == null) {
      throw new IllegalArgumentException("Verifikationsrad ${lineIndex} saknas.")
    }
    String accountNumber = normalizeAccountNumber(line.accountNumber)
    GroovyRowResult account = sql.firstRow('''
        select account_number as accountNumber,
               account_name as accountName,
               active
          from account
         where account_number = ?
    ''', [accountNumber]) as GroovyRowResult
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
        accountNumber,
        account.get('accountName') as String,
        normalizeLineDescription(line.description),
        debit,
        credit
    )
  }

  private static void validateBalanced(List<VoucherLine> safeLines) {
    if (safeLines.size() < 2) {
      throw new IllegalArgumentException('En bokförd verifikation måste ha minst två rader.')
    }
    BigDecimal debitTotal = safeLines.sum(BigDecimal.ZERO) { VoucherLine line -> line.debitAmount } as BigDecimal
    BigDecimal creditTotal = safeLines.sum(BigDecimal.ZERO) { VoucherLine line -> line.creditAmount } as BigDecimal
    if (debitTotal <= BigDecimal.ZERO) {
      throw new IllegalArgumentException('Debetbelopp måste vara större än noll.')
    }
    if (debitTotal != creditTotal) {
      throw new IllegalArgumentException("Verifikationen balanserar inte: debet ${debitTotal}, kredit ${creditTotal}.")
    }
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

  private static void ensurePostingAllowed(
      Sql sql,
      long fiscalYearId,
      LocalDate accountingDate,
      VoucherStatus targetStatus,
      boolean allowReportedVatPeriod,
      boolean allowLockedPeriod
  ) {
    GroovyRowResult row = sql.firstRow('''
        select locked
          from accounting_period
         where fiscal_year_id = ?
           and ? between start_date and end_date
    ''', [fiscalYearId, Date.valueOf(accountingDate)]) as GroovyRowResult
    if (row == null) {
      throw new IllegalStateException('Bokföringsdatumet saknar bokföringsperiod.')
    }
    if (Boolean.TRUE == row.get('locked') && !allowLockedPeriod) {
      throw new LockedAccountingPeriodException('Perioden är låst och kan inte bokföras på.')
    }
    VatService.ensurePeriodsForFiscalYear(sql, fiscalYearId)
    GroovyRowResult vatRow = sql.firstRow('''
        select status
          from vat_period
         where fiscal_year_id = ?
           and ? between start_date and end_date
    ''', [fiscalYearId, Date.valueOf(accountingDate)]) as GroovyRowResult
    if (vatRow == null) {
      return
    }
    String vatStatus = vatRow.get('status') as String
    if (VatService.LOCKED == vatStatus && !allowLockedPeriod) {
      throw new IllegalStateException('Momsperioden är låst och tillåter inte fler bokningar.')
    }
    if (VatService.REPORTED == vatStatus && targetStatus != VoucherStatus.CORRECTION && !allowReportedVatPeriod) {
      throw new IllegalStateException('Momsperioden är rapporterad. Använd korrigeringsflödet för ändringar.')
    }
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

  private static String calculateContentHash(
      Voucher voucher,
      List<VoucherLine> lines,
      VoucherStatus status,
      int runningNumber,
      String voucherNumber,
      String previousHash
  ) {
    StringBuilder payload = new StringBuilder()
    payload.append(previousHash ?: '').append('\n')
    payload.append(voucher.id).append('|')
    payload.append(voucher.fiscalYearId).append('|')
    payload.append(voucher.voucherSeriesId).append('|')
    payload.append(voucher.seriesCode).append('|')
    payload.append(runningNumber).append('|')
    payload.append(voucherNumber).append('|')
    payload.append(voucher.accountingDate).append('|')
    payload.append(voucher.description).append('|')
    payload.append(status.name()).append('|')
    payload.append(voucher.originalVoucherId ?: '').append('\n')
    new ArrayList<>(lines).sort { VoucherLine line -> line.lineIndex }.each { VoucherLine line ->
      payload.append(line.lineIndex).append('|')
      payload.append(line.accountNumber).append('|')
      payload.append(line.description ?: '').append('|')
      payload.append(line.debitAmount.toPlainString()).append('|')
      payload.append(line.creditAmount.toPlainString()).append('\n')
    }
    MessageDigest digest = MessageDigest.getInstance('SHA-256')
    byte[] hash = digest.digest(payload.toString().getBytes(StandardCharsets.UTF_8))
    HexFormat.of().formatHex(hash)
  }

  private static long resolveCompanyId(Sql sql, long fiscalYearId) {
    GroovyRowResult row = sql.firstRow(
        'select company_id as companyId from fiscal_year where id = ?',
        [fiscalYearId]
    ) as GroovyRowResult
    if (row == null) {
      throw new IllegalArgumentException("Okänt räkenskapsår: ${fiscalYearId}")
    }
    ((Number) row.get('companyId')).longValue()
  }

  private static ChainHead lockChainHead(Sql sql, long companyId) {
    GroovyRowResult row = sql.firstRow('''
        select company_id as id,
               last_content_hash as lastContentHash
          from voucher_chain_head
         where company_id = ?
         for update
    ''', [companyId]) as GroovyRowResult
    if (row == null) {
      throw new IllegalStateException('Kedjehuvudet för verifikationer saknas.')
    }
    new ChainHead(
        ((Number) row.get('id')).longValue(),
        row.get('lastContentHash') as String
    )
  }

  private static void updateChainHead(Sql sql, long companyId, String contentHash) {
    int updated = sql.executeUpdate('''
        update voucher_chain_head
           set last_content_hash = ?,
               updated_at = current_timestamp
         where company_id = ?
    ''', [contentHash, companyId])
    if (updated != 1) {
      throw new IllegalStateException('Kedjehuvudet för verifikationer kunde inte uppdateras.')
    }
  }

  private static LocalDateTime currentDatabaseTimestamp(Sql sql) {
    GroovyRowResult row = sql.firstRow('select current_timestamp as bookedAt') as GroovyRowResult
    SqlValueMapper.toLocalDateTime(row.get('bookedAt'))
  }

  private static void replaceLines(Sql sql, long voucherId, List<VoucherLine> lines) {
    sql.executeUpdate('delete from voucher_line where voucher_id = ?', [voucherId])
    lines.eachWithIndex { VoucherLine line, int index ->
      sql.executeInsert('''
          insert into voucher_line (
              voucher_id,
              line_index,
              account_number,
              line_description,
              debit_amount,
              credit_amount,
              created_at
          ) values (?, ?, ?, ?, ?, ?, current_timestamp)
      ''', [
          voucherId,
          index + 1,
          line.accountNumber,
          line.description,
          line.debitAmount,
          line.creditAmount
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
               v.original_voucher_id as originalVoucherId,
               v.previous_hash as previousHash,
               v.content_hash as contentHash,
               v.booked_at as bookedAt
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
               vl.account_number as accountNumber,
               a.account_name as accountName,
               vl.line_description as lineDescription,
               vl.debit_amount as debitAmount,
               vl.credit_amount as creditAmount
          from voucher_line vl
          join account a on a.account_number = vl.account_number
         where vl.voucher_id = ?
         order by vl.line_index
    ''', [voucherId]).collect { GroovyRowResult row ->
      new VoucherLine(
          Long.valueOf(row.get('id').toString()),
          Long.valueOf(row.get('voucherId').toString()),
          ((Number) row.get('lineIndex')).intValue(),
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
        row.get('previousHash') as String,
        row.get('contentHash') as String,
        SqlValueMapper.toLocalDateTime(row.get('bookedAt')),
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

  private static final class ChainHead {

    final long id
    final String lastContentHash

    private ChainHead(long id, String lastContentHash) {
      this.id = id
      this.lastContentHash = lastContentHash
    }
  }

  static final class PostingPermissions {

    static final PostingPermissions DEFAULT = new PostingPermissions(false, false)

    final boolean allowReportedVatPeriod
    final boolean allowLockedPeriod

    PostingPermissions(boolean allowReportedVatPeriod, boolean allowLockedPeriod) {
      this.allowReportedVatPeriod = allowReportedVatPeriod
      this.allowLockedPeriod = allowLockedPeriod
    }
  }
}
