package se.alipsa.accounting.service

import groovy.sql.GroovyRowResult
import groovy.sql.Sql

import alipsa.sieparser.SIE
import alipsa.sieparser.SieAccount
import alipsa.sieparser.SieBookingYear
import alipsa.sieparser.SieCompany
import alipsa.sieparser.SieDocument
import alipsa.sieparser.SieDocumentReader
import alipsa.sieparser.SieDocumentWriter
import alipsa.sieparser.SiePeriodValue
import alipsa.sieparser.SieType
import alipsa.sieparser.SieVoucher
import alipsa.sieparser.SieVoucherRow

import se.alipsa.accounting.domain.AccountSubgroup
import se.alipsa.accounting.domain.Company
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.ImportJob
import se.alipsa.accounting.domain.ImportJobStatus
import se.alipsa.accounting.domain.VoucherLine

import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Date
import java.text.Normalizer
import java.time.LocalDate

/**
 * Imports and exports SIE4 files and records import job outcomes.
 */
final class SieImportExportService {
  private static final int AMOUNT_SCALE = 2
  private static final long MAX_IMPORT_FILE_SIZE_BYTES = 50L * 1024L * 1024L
  private static final String PROGRAM_NAME = 'Alipsa Accounting'
  private static final String PROGRAM_VERSION = '1.0.0'
  private static final String GENERATOR_NAME = 'desktop-app'
  private static final Set<String> INCOME_KEYWORDS = [
      'INTAKT', 'RANTEINTAKT', 'RANTEINTAKTER', 'VINST', 'ERHALL', 'ERHALLNA', 'ERHALLET', 'UTDELNING',
      'BIDRAG', 'OVERSKOTT', 'ATERFORING', 'RABATT'
  ] as Set<String>
  private static final Set<String> EXPENSE_KEYWORDS = [
      'KOSTNAD', 'KOSTNADER', 'FORLUST', 'FORLUSTER', 'RANTEKOSTNAD',
      'RANTEKOSTNADER', 'SKATT', 'NEDSKRIVNING', 'NEDSKRIVNINGAR',
      'AVSATTNING', 'LAMNADE', 'UTGIFT', 'UTGIFTER'
  ] as Set<String>
  private final DatabaseService databaseService
  private final AccountingPeriodService accountingPeriodService
  private final VoucherService voucherService
  private final CompanyService companyService
  private final ReportIntegrityService reportIntegrityService
  private final AuditLogService auditLogService
  SieImportExportService() {
    this(
        DatabaseService.instance,
        new AccountingPeriodService(DatabaseService.instance),
        new VoucherService(DatabaseService.instance),
        new CompanyService(DatabaseService.instance),
        new ReportIntegrityService(),
        new AuditLogService(DatabaseService.instance)
    )
  }
  SieImportExportService(
      DatabaseService databaseService,
      AccountingPeriodService accountingPeriodService,
      VoucherService voucherService,
      CompanyService companyService,
      ReportIntegrityService reportIntegrityService,
      AuditLogService auditLogService
  ) {
    this.databaseService = databaseService
    this.accountingPeriodService = accountingPeriodService
    this.voucherService = voucherService
    this.companyService = companyService
    this.reportIntegrityService = reportIntegrityService
    this.auditLogService = auditLogService
  }
  SieImportResult importFile(long companyId, Path filePath) {
    CompanyService.requireValidCompanyId(companyId)
    Path safePath = validateImportPath(filePath)
    byte[] content = Files.readAllBytes(safePath)
    String checksum = sha256(content)
    long jobId = createImportJob(companyId, safePath.fileName.toString(), checksum)
    ImportJob duplicate = markDuplicateIfNeeded(jobId, companyId, checksum)
    if (duplicate != null) {
      return new SieImportResult(duplicate, null, true, 0, 0, 0, 0, [])
    }
    Long resolvedFiscalYearId = null
    try {
      ParsedSie parsed = parseDocument(safePath)
      SieImportResult result = databaseService.withTransaction { Sql sql ->
        FiscalYear fiscalYear = resolveTargetFiscalYear(sql, companyId, parsed.document)
        resolvedFiscalYearId = fiscalYear.id
        ImportCounts counts = importDocument(sql, fiscalYear.id, parsed.document, parsed.warnings)
        String summary = buildSuccessSummary(counts, fiscalYear, parsed.warnings)
        ImportJob job = completeImportJob(
            sql,
            jobId,
            fiscalYear.id,
            ImportJobStatus.SUCCESS,
            summary,
            parsed.warnings
        )
        new SieImportResult(job, fiscalYear, false, counts.accountsCreated, counts.openingBalanceCount, counts.voucherCount, counts.lineCount, parsed.warnings)
      }
      auditLogService.logImport(
          "Importerade SIE ${result.job.fileName}",
          [
              "jobId=${result.job.id}",
              "fiscalYearId=${result.job.fiscalYearId}",
              "checksum=${result.job.checksumSha256}",
              "summary=${result.job.summary}"
          ].join('\n'),
          companyId
      )
      result
    } catch (Exception exception) {
      markFailed(jobId, resolvedFiscalYearId, exception)
      throw exception
    }
  }
  SieExportResult exportFiscalYear(long fiscalYearId, Path targetPath) {
    Path safeTarget = normalizeExportPath(targetPath)
    ExportPayload payload = databaseService.withSql { Sql sql ->
      long companyId = resolveCompanyId(sql, fiscalYearId)
      Company company = companyService.findById(companyId)
      if (company == null) {
        throw new IllegalStateException('Företagsuppgifter måste sparas innan SIE-export kan göras.')
      }
      reportIntegrityService.ensureReportingAllowed(companyId)
      buildExportPayload(sql, fiscalYearId, company)
    }
    byte[] content = renderDocument(payload.document)
    Files.createDirectories(safeTarget.parent)
    Files.write(safeTarget, content)
    String checksum = sha256(content)
    auditLogService.logExport(
        "Exporterade SIE ${payload.fiscalYear.name}",
        [
            "fiscalYearId=${payload.fiscalYear.id}",
            "path=${safeTarget.toAbsolutePath()}",
            "checksum=${checksum}",
            "accounts=${payload.accountCount}",
            "openingBalances=${payload.openingBalanceCount}",
            "vouchers=${payload.voucherCount}"
        ].join('\n'),
        payload.companyId
    )
    new SieExportResult(
        safeTarget.toAbsolutePath().normalize(),
        payload.fiscalYear,
        checksum,
        content.length,
        payload.accountCount,
        payload.openingBalanceCount,
        payload.voucherCount
    )
  }
  List<ImportJob> listImportJobs(long companyId, int limit = 20) {
    CompanyService.requireValidCompanyId(companyId)
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
  private static Path validateImportPath(Path filePath) {
    if (filePath == null) {
      throw new IllegalArgumentException('En SIE-fil måste väljas.')
    }
    Path normalized = filePath.toAbsolutePath().normalize()
    if (!Files.isRegularFile(normalized)) {
      throw new IllegalArgumentException("SIE-filen hittades inte: ${normalized}")
    }
    if (Files.size(normalized) > MAX_IMPORT_FILE_SIZE_BYTES) {
      throw new IllegalArgumentException('SIE-filen är för stor att importera. Max 50 MB stöds.')
    }
    normalized
  }
  private static Path normalizeExportPath(Path targetPath) {
    if (targetPath == null) {
      throw new IllegalArgumentException('En målfil måste väljas för export.')
    }
    Path normalized = targetPath.toAbsolutePath().normalize()
    if (normalized.parent == null) {
      throw new IllegalArgumentException("Ogiltig exportväg: ${normalized}")
    }
    normalized
  }
  private long createImportJob(long companyId, String fileName, String checksum) {
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
  private ImportJob markDuplicateIfNeeded(long jobId, long companyId, String checksum) {
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
      completeImportJob(sql, jobId, null, ImportJobStatus.DUPLICATE, summary, [])
    }
  }
  private static ParsedSie parseDocument(Path filePath) {
    SieDocumentReader reader = new SieDocumentReader()
    reader.throwErrors = false
    reader.acceptSIETypes = EnumSet.of(SieType.TYPE_4)
    SieDocument document = reader.readDocument(filePath.toString())

    List<String> errors = []
    if (document == null) {
      errors << 'SIE-filen kunde inte läsas.'
    } else if (document.getSIETYP() != 4) {
      errors << ("Endast SIE4 stöds för import, fick SIE${document.getSIETYP()}." as String)
    }
    reader.validationExceptions.each { Exception exception ->
      if (exception?.message) {
        errors << exception.message
      }
    }
    if (!errors.isEmpty()) {
      throw new IllegalArgumentException(errors.join('\n'))
    }

    List<String> warnings = []
    reader.validationWarnings.each { Exception exception ->
      if (exception?.message) {
        warnings << exception.message
      }
    }
    new ParsedSie(document, warnings)
  }

  private FiscalYear resolveTargetFiscalYear(Sql sql, long companyId, SieDocument document) {
    SieBookingYear bookingYear = selectPrimaryBookingYear(document)
    GroovyRowResult existing = sql.firstRow('''
        select id,
               name,
               start_date as startDate,
               end_date as endDate,
               closed,
               closed_at as closedAt
          from fiscal_year
         where company_id = ?
           and start_date = ?
           and end_date = ?
    ''', [companyId, Date.valueOf(bookingYear.start), Date.valueOf(bookingYear.end)]) as GroovyRowResult
    FiscalYear fiscalYear = existing == null
        ? createFiscalYear(sql, companyId, bookingYear.start, bookingYear.end)
        : mapFiscalYear(existing)
    ensureFiscalYearImportable(sql, fiscalYear)
    fiscalYear
  }

  private static SieBookingYear selectPrimaryBookingYear(SieDocument document) {
    if (document == null || document.getRars() == null || document.getRars().isEmpty()) {
      throw new IllegalArgumentException('SIE-filen saknar #RAR och kan inte kopplas till ett räkenskapsår.')
    }
    SieBookingYear current = document.getRars().get(0)
    if (current != null) {
      return current
    }
    document.getRars().values().sort { SieBookingYear year -> year.id }.first()
  }

  private FiscalYear createFiscalYear(Sql sql, long companyId, LocalDate startDate, LocalDate endDate) {
    String name = startDate.year == endDate.year
        ? startDate.year.toString()
        : "${startDate} - ${endDate}"
    FiscalYearService.createFiscalYear(
        sql,
        accountingPeriodService,
        companyId,
        name,
        startDate,
        endDate,
        'SIE-filen överlappar ett befintligt räkenskapsår men matchar det inte exakt.'
    )
  }

  private static void ensureFiscalYearImportable(Sql sql, FiscalYear fiscalYear) {
    if (fiscalYear.closed) {
      throw new IllegalStateException("Räkenskapsåret ${fiscalYear.name} är stängt och kan inte användas för import.")
    }
    GroovyRowResult periodRow = sql.firstRow('''
        select count(*) as total
          from accounting_period
         where fiscal_year_id = ?
           and locked = true
    ''', [fiscalYear.id]) as GroovyRowResult
    if (((Number) periodRow.get('total')).intValue() > 0) {
      throw new IllegalStateException("Räkenskapsåret ${fiscalYear.name} har låsta perioder och kan inte användas för import.")
    }
    GroovyRowResult voucherRow = sql.firstRow(
        'select count(*) as total from voucher where fiscal_year_id = ?',
        [fiscalYear.id]
    ) as GroovyRowResult
    GroovyRowResult openingRow = sql.firstRow(
        'select count(*) as total from opening_balance where fiscal_year_id = ?',
        [fiscalYear.id]
    ) as GroovyRowResult
    if (((Number) voucherRow.get('total')).intValue() > 0 || ((Number) openingRow.get('total')).intValue() > 0) {
      throw new IllegalStateException("Räkenskapsåret ${fiscalYear.name} innehåller redan bokningar eller ingående balanser.")
    }
  }

  private ImportCounts importDocument(Sql sql, long fiscalYearId, SieDocument document, List<String> warnings) {
    int accountsCreated = upsertAccounts(sql, fiscalYearId, document.getKONTO().values() as Collection<SieAccount>)
    int openingBalanceCount = persistOpeningBalances(sql, fiscalYearId, document.getIB(), warnings)
    VoucherImportSummary voucherSummary = persistVouchers(sql, fiscalYearId, document.getVER())
    warnings.addAll(validateClosingBalances(sql, fiscalYearId, document.getUB()))
    new ImportCounts(accountsCreated, openingBalanceCount, voucherSummary.voucherCount, voucherSummary.lineCount)
  }

  private static String buildSuccessSummary(ImportCounts counts, FiscalYear fiscalYear, List<String> warnings) {
    String base = "Import klar för ${fiscalYear.name}: ${counts.accountsCreated} nya konton, " +
        "${counts.openingBalanceCount} ingående balanser, ${counts.voucherCount} verifikationer, ${counts.lineCount} rader."
    warnings.isEmpty() ? base : "${base} ${warnings.size()} varningar registrerades."
  }

  private int upsertAccounts(Sql sql, long fiscalYearId, Collection<SieAccount> accounts) {
    long companyId = resolveCompanyId(sql, fiscalYearId)
    int created = 0
    List<SieAccount> sortedAccounts = new ArrayList<>(accounts ?: [])
    sortedAccounts.sort { SieAccount account -> account.number }
    sortedAccounts.each { SieAccount account ->
      String accountNumber = normalizeAccountNumber(account.number)
      String accountName = normalizeAccountName(account.name) ?: accountNumber
      AccountClassification classification = classifyAccount(accountNumber, normalizeAccountName(account.name))
      GroovyRowResult existing = sql.firstRow(
          '''
          select account_number as accountNumber,
                 manual_review_required as manualReviewRequired
            from account
           where company_id = ?
             and account_number = ?
          ''',
          [companyId, accountNumber]
      ) as GroovyRowResult
      if (existing == null) {
        sql.executeInsert('''
            insert into account (
                company_id,
                account_number,
                account_name,
                account_class,
                normal_balance_side,
                vat_code,
                active,
                manual_review_required,
                classification_note,
                account_subgroup,
                created_at,
                updated_at
            ) values (?, ?, ?, ?, ?, ?, true, ?, ?, ?, current_timestamp, current_timestamp)
        ''', [
            companyId,
            accountNumber,
            accountName,
            classification.accountClass,
            classification.normalBalanceSide,
            null,
            classification.manualReviewRequired,
            classification.note,
            classification.accountSubgroup
        ])
        created++
      } else if (Boolean.TRUE == existing.get('manualReviewRequired')) {
        sql.executeUpdate('''
            update account
               set account_name = ?,
                   account_class = ?,
                   normal_balance_side = ?,
                   manual_review_required = ?,
                   classification_note = ?,
                   account_subgroup = ?,
                   updated_at = current_timestamp
             where company_id = ?
               and account_number = ?
        ''', [
            accountName,
            classification.accountClass,
            classification.normalBalanceSide,
            classification.manualReviewRequired,
            classification.note,
            classification.accountSubgroup,
            companyId,
            accountNumber
        ])
      } else {
        sql.executeUpdate('''
            update account
               set account_name = ?,
                   account_subgroup = ?,
                   updated_at = current_timestamp
             where company_id = ?
               and account_number = ?
        ''', [
            accountName,
            classification.accountSubgroup,
            companyId,
            accountNumber
        ])
      }
    }
    created
  }

  private static int persistOpeningBalances(Sql sql, long fiscalYearId, List<SiePeriodValue> balances, List<String> warnings) {
    long companyId = resolveCompanyId(sql, fiscalYearId)
    Map<String, BigDecimal> normalizedBalances = [:]
    (balances ?: []).each { SiePeriodValue value ->
      String accountNumber = normalizeAccountNumber(value.account?.number)
      if (!accountExists(sql, companyId, accountNumber)) {
        warnings << ("Ingående balans för konto ${accountNumber} hoppades över eftersom kontot inte finns." as String)
        return
      }
      if (!isBalanceAccount(sql, companyId, accountNumber)) {
        warnings << ("Ingående balans för konto ${accountNumber} hoppades över eftersom kontot inte är ett balanskonto." as String)
        return
      }
      if (normalizedBalances.containsKey(accountNumber)) {
        warnings << ("Flera #IB-rader hittades för konto ${accountNumber}; sista värdet användes." as String)
      }
      normalizedBalances[accountNumber] = scale(value.amount)
    }
    normalizedBalances.each { String accountNumber, BigDecimal amount ->
      GroovyRowResult account = sql.firstRow(
          'select id from account where company_id = ? and account_number = ?',
          [companyId, accountNumber]
      ) as GroovyRowResult
      if (account == null) {
        throw new IllegalStateException(
            "Kan inte hitta konto ${accountNumber} vid import av ingående balanser."
        )
      }
      long accountId = ((Number) account.get('id')).longValue()
      sql.executeInsert('''
          insert into opening_balance (
              fiscal_year_id,
              account_id,
              amount,
              created_at,
              updated_at
          ) values (?, ?, ?, current_timestamp, current_timestamp)
      ''', [fiscalYearId, accountId, amount])
    }
    normalizedBalances.size()
  }

  private VoucherImportSummary persistVouchers(Sql sql, long fiscalYearId, List<SieVoucher> vouchers) {
    List<SieVoucher> sortedVouchers = new ArrayList<>(vouchers ?: [])
    sortedVouchers.sort { SieVoucher left, SieVoucher right ->
      compareVoucherKey(left, right)
    }
    int voucherCount = 0
    int lineCount = 0
    for (SieVoucher voucher : sortedVouchers) {
      List<VoucherLine> lines = buildVoucherLines(voucher)
      voucherService.createVoucher(
          sql,
          fiscalYearId,
          normalizeSeriesCode(voucher.series),
          voucher.voucherDate,
          normalizeVoucherText(voucher.text),
          lines
      )
      voucherCount++
      lineCount += lines.size()
    }
    new VoucherImportSummary(voucherCount, lineCount)
  }

  private static int compareVoucherKey(SieVoucher left, SieVoucher right) {
    int dateCompare = left.voucherDate <=> right.voucherDate
    if (dateCompare != 0) {
      return dateCompare
    }
    int seriesCompare = (left.series ?: '') <=> (right.series ?: '')
    if (seriesCompare != 0) {
      return seriesCompare
    }
    compareVoucherNumbers(left.number, right.number)
  }

  private static int compareVoucherNumbers(String left, String right) {
    try {
      return Integer.valueOf(left ?: '0') <=> Integer.valueOf(right ?: '0')
    } catch (NumberFormatException ignored) {
      return (left ?: '') <=> (right ?: '')
    }
  }

  private static List<VoucherLine> buildVoucherLines(SieVoucher voucher) {
    if (voucher == null || voucher.voucherDate == null) {
      throw new IllegalArgumentException('En importerad verifikation saknar datum.')
    }
    List<VoucherLine> lines = []
    int lineIndex = 1
    voucher.rows.each { SieVoucherRow row ->
      BigDecimal amount = scale(row.amount)
      if (amount == BigDecimal.ZERO) {
        return
      }
      String accountNumber = normalizeAccountNumber(row.account?.number)
      BigDecimal debitAmount = amount > BigDecimal.ZERO ? amount : BigDecimal.ZERO
      BigDecimal creditAmount = amount < BigDecimal.ZERO ? amount.abs() : BigDecimal.ZERO
      lines << new VoucherLine(
          null,
          null,
          lineIndex++,
          null,
          accountNumber,
          null,
          normalizeLineText(row.text ?: voucher.text ?: ''),
          debitAmount,
          creditAmount
      )
    }
    if (lines.size() < 2) {
      throw new IllegalArgumentException("Verifikationen ${voucher.series ?: 'A'}-${voucher.number ?: '?'} saknar tillräckliga transaktionsrader.")
    }
    lines
  }

  private static List<String> validateClosingBalances(Sql sql, long fiscalYearId, List<SiePeriodValue> closingBalances) {
    Map<String, BigDecimal> expected = loadClosingBalances(sql, fiscalYearId)
    Map<String, BigDecimal> seen = [:]
    List<String> warnings = []
    (closingBalances ?: []).each { SiePeriodValue value ->
      String accountNumber = normalizeAccountNumber(value.account?.number)
      BigDecimal actual = scale(value.amount)
      BigDecimal expectedAmount = expected[accountNumber] ?: BigDecimal.ZERO.setScale(AMOUNT_SCALE)
      seen[accountNumber] = actual
      if (actual != expectedAmount) {
        warnings << ("Utgående balans för konto ${accountNumber} skiljer sig från importerade verifikationer: SIE ${actual.toPlainString()}, beräknat ${expectedAmount.toPlainString()}." as String)
      }
    }
    expected.each { String accountNumber, BigDecimal expectedAmount ->
      if (expectedAmount != BigDecimal.ZERO.setScale(AMOUNT_SCALE) && !seen.containsKey(accountNumber)) {
        warnings << ("SIE-filen saknar #UB för konto ${accountNumber} trots att beräknat utgående saldo är ${expectedAmount.toPlainString()}." as String)
      }
    }
    warnings
  }

  private static Map<String, BigDecimal> loadClosingBalances(Sql sql, long fiscalYearId) {
    Map<String, BigDecimal> openings = [:]
    sql.rows('''
        select a.account_number as accountNumber,
               ob.amount
          from opening_balance ob
          join account a on a.id = ob.account_id
         where ob.fiscal_year_id = ?
           and a.account_class in ('ASSET', 'LIABILITY', 'EQUITY')
    ''', [fiscalYearId]).each { GroovyRowResult row ->
      openings[row.get('accountNumber') as String] = scale(new BigDecimal(row.get('amount').toString()))
    }

    Map<String, BigDecimal> movements = [:]
    sql.rows('''
        select vl.account_number as accountNumber,
               a.normal_balance_side as normalBalanceSide,
               sum(vl.debit_amount) as debitAmount,
               sum(vl.credit_amount) as creditAmount
          from voucher v
          join voucher_line vl on vl.voucher_id = v.id
          join account a on a.id = vl.account_id
         where v.fiscal_year_id = ?
           and v.status in ('ACTIVE', 'CORRECTION')
           and a.account_class in ('ASSET', 'LIABILITY', 'EQUITY')
         group by vl.account_number, a.normal_balance_side
    ''', [fiscalYearId]).each { GroovyRowResult row ->
      movements[row.get('accountNumber') as String] = signedAmount(
          new BigDecimal(row.get('debitAmount').toString()),
          new BigDecimal(row.get('creditAmount').toString()),
          row.get('normalBalanceSide') as String
      )
    }

    Set<String> accounts = []
    accounts.addAll(openings.keySet())
    accounts.addAll(movements.keySet())

    Map<String, BigDecimal> closing = [:]
    accounts.each { String accountNumber ->
      closing[accountNumber] = scale((openings[accountNumber] ?: BigDecimal.ZERO) + (movements[accountNumber] ?: BigDecimal.ZERO))
    }
    closing
  }

  private static ExportPayload buildExportPayload(Sql sql, long fiscalYearId, Company company) {
    FiscalYear fiscalYear = findFiscalYear(sql, fiscalYearId)
    if (fiscalYear == null) {
      throw new IllegalArgumentException("Okänt räkenskapsår: ${fiscalYearId}")
    }

    Map<String, AccountSeed> accounts = loadAccounts(sql, company.id)
    Map<String, BigDecimal> openings = loadOpeningBalances(sql, fiscalYearId)
    List<ExportVoucherSeed> vouchers = loadBookedVouchers(sql, fiscalYearId)
    Map<String, BigDecimal> closings = loadClosingBalances(sql, fiscalYearId)

    SieDocument document = new SieDocument()
    document.setFLAGGA(0)
    document.setSIETYP(4)
    document.setGEN_DATE(LocalDate.now())
    document.setGEN_NAMN(GENERATOR_NAME)
    document.setOMFATTN(fiscalYear.endDate)
    document.setPROGRAM([PROGRAM_NAME, PROGRAM_VERSION])
    document.setVALUTA(company.defaultCurrency?.trim()?.toUpperCase(Locale.ROOT) ?: 'SEK')
    document.setFNAMN(new SieCompany())
    document.getFNAMN().setName(company.companyName)
    document.getFNAMN().setOrgIdentifier(company.organizationNumber)
    document.setRars([(0): buildBookingYear(fiscalYear)])

    Map<String, SieAccount> konto = [:]
    new TreeMap<String, AccountSeed>(accounts).each { String accountNumber, AccountSeed seed ->
      SieAccount account = new SieAccount(accountNumber, seed.accountName)
      konto[accountNumber] = account
    }
    document.setKONTO(konto)
    document.setIB(buildOpeningBalances(document, openings))
    document.setUB(buildClosingBalances(document, closings))
    document.setVER(buildExportVouchers(document, vouchers))

    new ExportPayload(
        company.id,
        document,
        fiscalYear,
        accounts.size(),
        openings.size(),
        vouchers.size()
    )
  }

  private static SieBookingYear buildBookingYear(FiscalYear fiscalYear) {
    SieBookingYear bookingYear = new SieBookingYear()
    bookingYear.id = 0
    bookingYear.start = fiscalYear.startDate
    bookingYear.end = fiscalYear.endDate
    bookingYear
  }

  private static Map<String, AccountSeed> loadAccounts(Sql sql, long companyId) {
    Map<String, AccountSeed> accounts = [:]
    sql.rows('''
        select account_number as accountNumber,
               account_name as accountName,
               account_class as accountClass,
               normal_balance_side as normalBalanceSide
          from account
         where company_id = ?
         order by account_number
    ''', [companyId]).each { GroovyRowResult row ->
      accounts[row.get('accountNumber') as String] = new AccountSeed(
          row.get('accountName') as String,
          row.get('accountClass') as String,
          row.get('normalBalanceSide') as String
      )
    }
    accounts
  }

  private static Map<String, BigDecimal> loadOpeningBalances(Sql sql, long fiscalYearId) {
    Map<String, BigDecimal> balances = [:]
    sql.rows('''
        select a.account_number as accountNumber,
               ob.amount
          from opening_balance ob
          join account a on a.id = ob.account_id
         where ob.fiscal_year_id = ?
    ''', [fiscalYearId]).each { GroovyRowResult row ->
      balances[row.get('accountNumber') as String] = scale(new BigDecimal(row.get('amount').toString()))
    }
    balances
  }

  private static List<ExportVoucherSeed> loadBookedVouchers(Sql sql, long fiscalYearId) {
    Map<Long, ExportVoucherSeed> vouchers = [:]
    sql.rows('''
        select v.id as voucherId,
               s.series_code as seriesCode,
               v.running_number as runningNumber,
               v.accounting_date as accountingDate,
               v.description as description,
               vl.line_index as lineIndex,
               vl.account_number as accountNumber,
               vl.line_description as lineDescription,
               vl.debit_amount as debitAmount,
               vl.credit_amount as creditAmount
          from voucher v
          join voucher_series s on s.id = v.voucher_series_id
          join voucher_line vl on vl.voucher_id = v.id
         where v.fiscal_year_id = ?
           and v.status in ('ACTIVE', 'CORRECTION')
         order by v.accounting_date, v.running_number, v.id, vl.line_index
    ''', [fiscalYearId]).each { GroovyRowResult row ->
      long voucherId = ((Number) row.get('voucherId')).longValue()
      ExportVoucherSeed seed = vouchers[voucherId]
      if (seed == null) {
        seed = new ExportVoucherSeed(
            voucherId,
            row.get('seriesCode') as String,
            row.get('runningNumber') == null ? null : Integer.valueOf(row.get('runningNumber').toString()),
            SqlValueMapper.toLocalDate(row.get('accountingDate')),
            row.get('description') as String,
            []
        )
        vouchers[voucherId] = seed
      }
      seed.lines << new ExportLineSeed(
          ((Number) row.get('lineIndex')).intValue(),
          row.get('accountNumber') as String,
          row.get('lineDescription') as String,
          scale(new BigDecimal(row.get('debitAmount').toString())),
          scale(new BigDecimal(row.get('creditAmount').toString()))
      )
    }
    vouchers.values() as List<ExportVoucherSeed>
  }

  private static List<SiePeriodValue> buildOpeningBalances(SieDocument document, Map<String, BigDecimal> openings) {
    List<SiePeriodValue> rows = []
    new TreeMap<String, BigDecimal>(openings).each { String accountNumber, BigDecimal amount ->
      SiePeriodValue value = new SiePeriodValue()
      value.yearNr = 0
      value.account = document.getKONTO().get(accountNumber)
      value.amount = amount
      value.token = SIE.IB
      rows << value
    }
    rows
  }

  private static List<SiePeriodValue> buildClosingBalances(SieDocument document, Map<String, BigDecimal> closings) {
    List<SiePeriodValue> rows = []
    new TreeMap<String, BigDecimal>(closings).each { String accountNumber, BigDecimal amount ->
      SiePeriodValue value = new SiePeriodValue()
      value.yearNr = 0
      value.account = document.getKONTO().get(accountNumber)
      value.amount = amount
      value.token = SIE.UB
      rows << value
    }
    rows
  }

  private static List<SieVoucher> buildExportVouchers(SieDocument document, List<ExportVoucherSeed> vouchers) {
    vouchers.collect { ExportVoucherSeed voucherSeed ->
      SieVoucher voucher = new SieVoucher()
      voucher.series = voucherSeed.seriesCode
      voucher.number = voucherSeed.runningNumber?.toString() ?: voucherSeed.voucherId.toString()
      voucher.voucherDate = voucherSeed.accountingDate
      voucher.text = voucherSeed.description ?: ''
      voucher.token = SIE.VER
      voucher.rows = voucherSeed.lines.sort { ExportLineSeed line -> line.lineIndex }.collect { ExportLineSeed line ->
        SieVoucherRow row = new SieVoucherRow()
        row.account = document.getKONTO().get(line.accountNumber)
        row.amount = line.debitAmount > BigDecimal.ZERO ? line.debitAmount : line.creditAmount.negate()
        row.rowDate = voucherSeed.accountingDate
        row.text = line.lineDescription ?: voucherSeed.description ?: ''
        row.token = SIE.TRANS
        row
      }
      voucher
    }
  }

  private static byte[] renderDocument(SieDocument document) {
    ByteArrayOutputStream output = new ByteArrayOutputStream()
    new SieDocumentWriter(document).write(output)
    output.toByteArray()
  }

  private static ImportJob completeImportJob(
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
    findImportJob(sql, jobId)
  }

  private void markFailed(long jobId, Long fiscalYearId, Exception exception) {
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

  private static ImportJob findImportJob(Sql sql, long jobId) {
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

  private static ImportJob mapImportJob(GroovyRowResult row) {
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

  private static FiscalYear findFiscalYear(Sql sql, long fiscalYearId) {
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

  private static boolean isBalanceAccount(Sql sql, long companyId, String accountNumber) {
    String accountClass = lookupAccountClass(sql, companyId, accountNumber)
    accountClass in ['ASSET', 'LIABILITY', 'EQUITY']
  }

  private static boolean accountExists(Sql sql, long companyId, String accountNumber) {
    lookupAccountClass(sql, companyId, accountNumber) != null
  }

  private static String lookupAccountClass(Sql sql, long companyId, String accountNumber) {
    GroovyRowResult row = sql.firstRow(
        'select account_class as accountClass from account where company_id = ? and account_number = ?',
        [companyId, accountNumber]
    ) as GroovyRowResult
    row?.get('accountClass') as String
  }

  private static BigDecimal signedAmount(BigDecimal debitAmount, BigDecimal creditAmount, String normalBalanceSide) {
    String safeNormalBalanceSide = normalBalanceSide?.trim()?.toUpperCase(Locale.ROOT)
    if (!safeNormalBalanceSide) {
      throw new IllegalStateException('Kontot saknar normal balanssida för SIE-avstämning.')
    }
    safeNormalBalanceSide == 'DEBIT'
        ? scale(debitAmount - creditAmount)
        : scale(creditAmount - debitAmount)
  }

  private static long resolveCompanyId(Sql sql, long fiscalYearId) {
    CompanyService.resolveFromFiscalYear(sql, fiscalYearId)
  }

  private long resolveCompanyId(long fiscalYearId) {
    databaseService.withSql { Sql sql ->
      CompanyService.resolveFromFiscalYear(sql, fiscalYearId)
    }
  }

  private static String normalizeAccountNumber(String accountNumber) {
    String normalized = accountNumber?.trim()
    if (!(normalized ==~ /\d{4}/)) {
      throw new IllegalArgumentException("Ogiltigt kontonummer i SIE-filen: ${accountNumber}")
    }
    normalized
  }

  private static String normalizeSeriesCode(String seriesCode) {
    String normalized = seriesCode?.trim()?.toUpperCase(Locale.ROOT)
    if (!normalized) {
      return 'A'
    }
    if (!(normalized ==~ /[A-Z0-9]{1,8}/)) {
      throw new IllegalArgumentException("Verifikationsserien ${seriesCode} stöds inte av applikationen.")
    }
    normalized
  }

  private static String normalizeVoucherText(String value) {
    String normalized = value?.trim()
    normalized ? truncate(normalized, 500) : 'Importerad SIE-verifikation'
  }

  private static String normalizeLineText(String value) {
    String normalized = value?.trim()
    normalized ? truncate(normalized, 500) : null
  }

  private static String normalizeAccountName(String value) {
    (value ?: '')
        .replaceAll(/\s+/, ' ')
        .trim()
  }

  private static BigDecimal scale(BigDecimal amount) {
    (amount ?: BigDecimal.ZERO).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)
  }

  private static String sha256(byte[] content) {
    HexFormat.of().formatHex(java.security.MessageDigest.getInstance('SHA-256').digest(content))
  }

  private static String truncate(String value, int maxLength) {
    if (value == null) {
      return null
    }
    value.length() <= maxLength ? value : value.substring(0, maxLength)
  }

  private static AccountClassification classifyAccount(String accountNumber, String accountName) {
    int prefix = Integer.parseInt(accountNumber.substring(0, 1))
    int subgroup = Integer.parseInt(accountNumber.substring(0, 2))
    String accountSubgroup = AccountSubgroup.fromAccountNumber(accountNumber)?.name()
    switch (prefix) {
      case 1:
        return new AccountClassification('ASSET', 'DEBIT', false, null, accountSubgroup)
      case 2:
        if (subgroup <= 20) {
          return new AccountClassification('EQUITY', 'CREDIT', false, null, accountSubgroup)
        }
        return new AccountClassification('LIABILITY', 'CREDIT', false, null, accountSubgroup)
      case 3:
        return new AccountClassification('INCOME', 'CREDIT', false, null, accountSubgroup)
      case 4:
      case 5:
      case 6:
      case 7:
        return new AccountClassification('EXPENSE', 'DEBIT', false, null, accountSubgroup)
      case 8:
        return classifyMixedResultAccount(accountNumber, accountName)
      default:
        return new AccountClassification(
            null,
            null,
            true,
            'Kontot kunde inte klassificeras automatiskt från SIE-importen.',
            accountSubgroup
        )
    }
  }

  private static AccountClassification classifyMixedResultAccount(String accountNumber, String accountName) {
    String accountSubgroup = AccountSubgroup.fromAccountNumber(accountNumber)?.name()
    String normalized = stripDiacritics(accountName).toUpperCase(Locale.ROOT)
    boolean incomeMatch = INCOME_KEYWORDS.any { String keyword -> normalized.contains(keyword) }
    boolean expenseMatch = EXPENSE_KEYWORDS.any { String keyword -> normalized.contains(keyword) }
    if (incomeMatch && !expenseMatch) {
      return new AccountClassification('INCOME', 'CREDIT', false, null, accountSubgroup)
    }
    if (expenseMatch && !incomeMatch) {
      return new AccountClassification('EXPENSE', 'DEBIT', false, null, accountSubgroup)
    }
    new AccountClassification(
        null,
        null,
        true,
        'Kontot kräver manuell klassning eftersom BAS-gruppen innehåller både intäkter och kostnader.',
        accountSubgroup
    )
  }

  private static String stripDiacritics(String value) {
    if (!value) {
      return ''
    }
    String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
    normalized.replaceAll(/\p{M}+/, '')
  }

}
