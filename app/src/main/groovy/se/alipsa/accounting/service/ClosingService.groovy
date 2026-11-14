package se.alipsa.accounting.service

import groovy.sql.GroovyRowResult
import groovy.sql.Sql

import se.alipsa.accounting.domain.ClosingEntry
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.LegalForm
import se.alipsa.accounting.domain.Voucher
import se.alipsa.accounting.domain.VoucherLine
import se.alipsa.accounting.support.AmountFormatter

import java.math.RoundingMode
import java.sql.Date
import java.time.Clock
import java.time.LocalDate

/**
 * Handles year-end closing, retained earnings transfer and next-year opening balances.
 */
final class ClosingService {

  static final String DEFAULT_CLOSING_ACCOUNT = LegalForm.AKTIEBOLAG.defaultResultAccount
  static final String DEFAULT_CLOSING_SERIES = 'YE'
  static final String RESULT_CLOSING = 'RESULT_CLOSING'
  static final String OPENING_BALANCE = 'OPENING_BALANCE'

  private static final int AMOUNT_SCALE = 2

  private final DatabaseService databaseService
  private final AccountingPeriodService accountingPeriodService
  private final FiscalYearService fiscalYearService
  private final VoucherService voucherService
  private final ReportIntegrityService reportIntegrityService
  private final OpeningBalanceService openingBalanceService
  private final Clock clock

  ClosingService() {
    this(
        DatabaseService.instance,
        new AccountingPeriodService(DatabaseService.instance),
        new FiscalYearService(DatabaseService.instance),
        new VoucherService(DatabaseService.instance),
        new ReportIntegrityService(),
        new OpeningBalanceService(DatabaseService.instance),
        Clock.systemDefaultZone()
    )
  }

  ClosingService(
      DatabaseService databaseService,
      AccountingPeriodService accountingPeriodService,
      FiscalYearService fiscalYearService,
      VoucherService voucherService,
      ReportIntegrityService reportIntegrityService
  ) {
    this(
        databaseService,
        accountingPeriodService,
        fiscalYearService,
        voucherService,
        reportIntegrityService,
        new OpeningBalanceService(databaseService),
        Clock.systemDefaultZone()
    )
  }

  ClosingService(
      DatabaseService databaseService,
      AccountingPeriodService accountingPeriodService,
      FiscalYearService fiscalYearService,
      VoucherService voucherService,
      ReportIntegrityService reportIntegrityService,
      Clock clock
  ) {
    this(
        databaseService,
        accountingPeriodService,
        fiscalYearService,
        voucherService,
        reportIntegrityService,
        new OpeningBalanceService(databaseService),
        clock
    )
  }

  ClosingService(
      DatabaseService databaseService,
      AccountingPeriodService accountingPeriodService,
      FiscalYearService fiscalYearService,
      VoucherService voucherService,
      ReportIntegrityService reportIntegrityService,
      OpeningBalanceService openingBalanceService,
      Clock clock
  ) {
    this.databaseService = databaseService
    this.accountingPeriodService = accountingPeriodService
    this.fiscalYearService = fiscalYearService
    this.voucherService = voucherService
    this.reportIntegrityService = reportIntegrityService
    this.openingBalanceService = openingBalanceService
    this.clock = clock
  }

  String defaultClosingAccount(long fiscalYearId) {
    databaseService.withSql { Sql sql ->
      defaultClosingAccount(sql, resolveCompanyId(sql, fiscalYearId))
    }
  }

  private static String defaultClosingAccount(Sql sql, long companyId) {
    String legalForm = sql.firstRow('select legal_form from company where id = ?', [companyId]).get('legal_form') as String
    LegalForm.fromDatabaseValue(legalForm)?.defaultResultAccount ?: DEFAULT_CLOSING_ACCOUNT
  }

  YearEndClosingPreview previewClosing(long fiscalYearId, String closingAccountNumber = null) {
    long companyId = databaseService.withSql { Sql sql ->
      resolveCompanyId(sql, fiscalYearId)
    }
    List<String> integrityProblems = reportIntegrityService.listCriticalProblems(companyId)
    databaseService.withSql { Sql sql ->
      buildPreview(sql, fiscalYearId, closingAccountNumber, integrityProblems)
    }
  }

  YearEndClosingResult closeFiscalYear(long fiscalYearId, String closingAccountNumber = null) {
    long companyId = databaseService.withSql { Sql sql ->
      resolveCompanyId(sql, fiscalYearId)
    }
    reportIntegrityService.ensureOperationAllowed(companyId, 'Årsstängning')
    databaseService.withTransaction { Sql sql ->
      // Integrity is checked before opening the transaction so we do not re-run the
      // expensive cross-system validation while holding DB locks during closing.
      YearEndClosingPreview preview = buildPreview(sql, fiscalYearId, closingAccountNumber, [])
      if (!preview.blockingIssues.isEmpty()) {
        throw new IllegalStateException(preview.blockingIssues.join('\n'))
      }
      FiscalYear nextFiscalYear = preview.nextFiscalYearWillBeCreated
          ? ensureNextFiscalYear(sql, companyId, preview.fiscalYear)
          : preview.nextFiscalYear
      YearEndClosingPreview executionPreview = new YearEndClosingPreview(
          preview.fiscalYear,
          nextFiscalYear,
          false,
          preview.closingAccountNumber,
          preview.resultAccountsWithBalanceCount,
          preview.incomeTotal,
          preview.expenseTotal,
          preview.netResult,
          preview.transferredResult,
          preview.remainingResult,
          preview.blockingIssues,
          preview.warnings
      )
      ClosingExecution execution = executeClosing(sql, executionPreview)
      FiscalYear closedYear = fiscalYearService.closeFiscalYear(sql, fiscalYearId)
      new YearEndClosingResult(
          closedYear,
          nextFiscalYear,
          execution.closingVoucher,
          executionPreview.resultAccountsWithBalanceCount,
          execution.openingBalanceCount,
          execution.closingEntryCount,
          executionPreview.netResult,
          executionPreview.transferredResult,
          executionPreview.remainingResult,
          executionPreview.warnings
      )
    }
  }

  List<ClosingEntry> listClosingEntries(long fiscalYearId) {
    databaseService.withSql { Sql sql ->
      sql.rows('''
          select id,
                 fiscal_year_id as fiscalYearId,
                 next_fiscal_year_id as nextFiscalYearId,
                 voucher_id as voucherId,
                 entry_type as entryType,
                 account_id as accountId,
                 counter_account_id as counterAccountId,
                 amount,
                 created_at as createdAt
            from closing_entry
           where fiscal_year_id = ?
           order by id
      ''', [fiscalYearId]).collect { GroovyRowResult row ->
        mapClosingEntry(row)
      }
    }
  }

  private YearEndClosingPreview buildPreview(
      Sql sql,
      long fiscalYearId,
      String closingAccountNumber,
      List<String> integrityProblems
  ) {
    FiscalYear fiscalYear = requireFiscalYear(sql, fiscalYearId)
    long companyId = resolveCompanyId(sql, fiscalYear.id)
    String safeClosingAccount = normalizeAccountNumber(
        closingAccountNumber == null ? defaultClosingAccount(sql, companyId) : closingAccountNumber)
    List<String> blockers = []
    List<String> warnings = []

    if (fiscalYear.closed) {
      blockers << ("Räkenskapsåret ${fiscalYear.name} är redan stängt." as String)
    }
    if (hasClosingEntries(sql, fiscalYear.id)) {
      blockers << ("Räkenskapsåret ${fiscalYear.name} har redan bokslutsposter registrerade." as String)
    }
    if (!integrityProblems.isEmpty()) {
      String summary = integrityProblems.take(3).join('\n')
      blockers << ("Integritetskontrollerna måste vara gröna före årsstängning:\n${summary}" as String)
    }

    BalanceAccountSeed closingAccount = loadAccount(sql, companyId, safeClosingAccount)
    if (closingAccount == null) {
      blockers << ("Resultat förs mot konto ${safeClosingAccount}, men kontot saknas i kontoplanen." as String)
    } else {
      if (closingAccount.accountClass != 'EQUITY') {
        blockers << ("Konto ${safeClosingAccount} måste vara klassat som eget kapital för årsbokslut." as String)
      }
      if (!closingAccount.active) {
        blockers << ("Konto ${safeClosingAccount} måste vara aktivt för årsbokslut." as String)
      }
    }

    appendUnclassifiedAccountBlocker(sql, fiscalYear.id, blockers)

    Map<String, ResultAccountBalance> resultBalances = loadResultBalances(sql, fiscalYear.id)
    ClosingResultTotals totals = calculateResultTotals(resultBalances)
    appendTransferAccountBlocker(sql, companyId, totals.remainingResult, blockers)
    appendClosingWarnings(fiscalYear, resultBalances, totals, warnings)

    NextFiscalYearPlan nextPlan = planNextFiscalYear(sql, companyId, fiscalYear)
    if (nextPlan.conflictMessage != null) {
      blockers << nextPlan.conflictMessage
    } else if (nextPlan.fiscalYear?.id != null) {
      if (nextPlan.fiscalYear.closed) {
        blockers << ("Nästa räkenskapsår ${nextPlan.fiscalYear.name} är redan stängt." as String)
      } else if (openingBalanceService.hasVoucherActivity(sql, nextPlan.fiscalYear.id)) {
        blockers << ("Nästa räkenskapsår ${nextPlan.fiscalYear.name} innehåller redan verifikationer." as String)
      } else if (openingBalanceService.hasManualOpeningBalances(sql, nextPlan.fiscalYear.id)) {
        blockers << ("Nästa räkenskapsår ${nextPlan.fiscalYear.name} innehåller manuellt justerade ingående balanser." as String)
      } else if (openingBalanceService.hasAutoManagedOpeningBalances(sql, nextPlan.fiscalYear.id)) {
        warnings << ("Nästa räkenskapsårs automatiska ingående balanser kommer att uppdateras från ${fiscalYear.name}." as String)
      }
    }

    new YearEndClosingPreview(
        fiscalYear,
        nextPlan.fiscalYear,
        nextPlan.willCreate,
        safeClosingAccount,
        resultBalances.size(),
        totals.incomeTotal,
        totals.expenseTotal,
        totals.netResult,
        totals.transferredResult,
        totals.remainingResult,
        blockers,
        warnings
    )
  }

  private static ClosingResultTotals calculateResultTotals(Map<String, ResultAccountBalance> resultBalances) {
    Collection<ResultAccountBalance> operatingBalances = resultBalances.values().findAll { ResultAccountBalance balance ->
      balance.accountNumber != '8999'
    }
    BigDecimal incomeTotal = operatingBalances.findAll { ResultAccountBalance balance -> balance.accountClass == 'INCOME' }
        .sum(BigDecimal.ZERO) { ResultAccountBalance balance -> balance.amount } as BigDecimal
    BigDecimal expenseTotal = operatingBalances.findAll { ResultAccountBalance balance -> balance.accountClass == 'EXPENSE' }
        .sum(BigDecimal.ZERO) { ResultAccountBalance balance -> balance.amount } as BigDecimal
    // The class-based totals are display-only. The transfer must be derived from the
    // debit/credit direction of the entries, otherwise an account whose normal side
    // contradicts its class flips the sign of the result.
    BigDecimal naturalNet = operatingBalances.sum(BigDecimal.ZERO) { ResultAccountBalance balance ->
      naturalAmount(balance)
    } as BigDecimal
    ResultAccountBalance transfer = resultBalances['8999']
    BigDecimal netResult = scale(-naturalNet)
    BigDecimal transferredResult = transfer == null ? BigDecimal.ZERO : naturalAmount(transfer)
    new ClosingResultTotals(scale(incomeTotal), scale(expenseTotal), netResult,
        scale(transferredResult), scale(netResult - transferredResult))
  }

  private static BigDecimal naturalAmount(ResultAccountBalance balance) {
    // amount is signed by the account's normal side, so flipping credit-normal accounts
    // yields the debit-minus-credit direction regardless of classification.
    balance.normalBalanceSide == 'DEBIT' ? balance.amount : -balance.amount
  }

  private void appendClosingWarnings(
      FiscalYear fiscalYear,
      Map<String, ResultAccountBalance> resultBalances,
      ClosingResultTotals totals,
      List<String> warnings
  ) {
    if (totals.remainingResult == BigDecimal.ZERO) {
      warnings << 'Inget återstående resultat att överföra. Bokslutsverifikationen hoppas över.'
    }
    appendMisclassifiedResultAccountWarning(resultBalances, warnings)
    appendAlreadyTransferredResultWarning(totals, warnings)
    LocalDate dueDate = fiscalYear.endDate.plusMonths(6)
    if (LocalDate.now(clock).isAfter(dueDate)) {
      warnings << ("Bokslutsfristen passerade ${dueDate}. Årsbokslutet borde redan vara upprättat." as String)
    }
  }

  private static void appendMisclassifiedResultAccountWarning(
      Map<String, ResultAccountBalance> resultBalances,
      List<String> warnings
  ) {
    List<String> mismatched = resultBalances.values()
        .findAll { ResultAccountBalance balance ->
          (balance.accountClass == 'INCOME' && balance.normalBalanceSide == 'DEBIT') ||
              (balance.accountClass == 'EXPENSE' && balance.normalBalanceSide == 'CREDIT')
        }
        .collect { ResultAccountBalance balance -> "${balance.accountNumber} (${balance.accountClass}/${balance.normalBalanceSide})" as String }
    if (!mismatched.isEmpty()) {
      warnings << ("Följande resultatkonton har en normalsida som avviker från kontoklassen: ${mismatched.join(', ')}. " +
          'Resultatöverföringen beräknas från debet/kredit per konto och påverkas inte, men kontona bör rättas i kontoplanen.' as String)
    }
  }

  private static void appendTransferAccountBlocker(Sql sql, long companyId, BigDecimal remainingResult, List<String> blockers) {
    if (remainingResult != BigDecimal.ZERO) {
      BalanceAccountSeed transferAccount = loadAccount(sql, companyId, '8999')
      if (transferAccount == null || !transferAccount.active || transferAccount.accountClass != 'EXPENSE' ||
          transferAccount.normalBalanceSide != 'DEBIT') {
        blockers << 'Konto 8999 måste finnas, vara aktivt och klassat som kostnad med debet som normalsida för årsbokslut.'
      }
    }
  }

  private static void appendAlreadyTransferredResultWarning(ClosingResultTotals totals, List<String> warnings) {
    if (totals.transferredResult == BigDecimal.ZERO) {
      return
    }
    // Imported years may already contain some or all of the year-end transfer.
    warnings << ("Årets resultat har redan förts över via konto 8999 (${AmountFormatter.format(totals.transferredResult, null)}); " +
        "bokslutet överför återstående ${AmountFormatter.format(totals.remainingResult, null)}." as String)
  }

  private ClosingExecution executeClosing(Sql sql, YearEndClosingPreview preview) {
    Voucher closingVoucher = preview.remainingResult == BigDecimal.ZERO
        ? null
        : createClosingVoucher(sql, preview.fiscalYear, preview.closingAccountNumber, preview.remainingResult)

    long companyId = resolveCompanyId(sql, preview.fiscalYear.id)
    int closingEntryCount = persistResultClosingEntries(
        sql,
        preview.fiscalYear.id,
        closingVoucher?.id,
        preview.closingAccountNumber,
        preview.remainingResult
    )
    Map<String, BigDecimal> closingBalances = openingBalanceService.loadBalanceClosingBalances(sql, preview.fiscalYear.id)
    int openingBalanceCount = openingBalanceService.replaceYearEndClosingBalances(
        sql,
        preview.fiscalYear.id,
        preview.nextFiscalYear.id,
        closingBalances
    )
    closingEntryCount += persistOpeningBalanceEntries(sql, preview.fiscalYear.id, preview.nextFiscalYear.id, companyId, closingBalances)
    new ClosingExecution(closingVoucher, openingBalanceCount, closingEntryCount)
  }

  private Voucher createClosingVoucher(
      Sql sql,
      FiscalYear fiscalYear,
      String closingAccountNumber,
      BigDecimal remainingResult
  ) {
    List<VoucherLine> lines = buildClosingVoucherLines(remainingResult, closingAccountNumber)
    voucherService.createVoucherBypassLock(
        sql,
        fiscalYear.id,
        DEFAULT_CLOSING_SERIES,
        fiscalYear.endDate,
        "Årsbokslut ${fiscalYear.name}",
        lines
    )
  }

  private static List<VoucherLine> buildClosingVoucherLines(
      BigDecimal remainingResult,
      String closingAccountNumber
  ) {
    // Preserve each operating account; 8999 offsets their aggregate result in the ledger.
    BigDecimal zero = BigDecimal.ZERO.setScale(AMOUNT_SCALE)
    [
        new VoucherLine(null, null, 1, null, '8999', null, 'Årets resultat',
            remainingResult > zero ? remainingResult : zero,
            remainingResult < zero ? remainingResult.abs() : zero),
        new VoucherLine(null, null, 2, null, closingAccountNumber, null, "Årets resultat ${closingAccountNumber}",
            remainingResult < zero ? remainingResult.abs() : zero,
            remainingResult > zero ? remainingResult : zero)
    ]
  }

  private static int persistResultClosingEntries(
      Sql sql,
      long fiscalYearId,
      Long voucherId,
      String closingAccountNumber,
      BigDecimal amount
  ) {
    if (voucherId == null) {
      return 0
    }
    long companyId = resolveCompanyId(sql, fiscalYearId)
    long accountId = resolveAccountId(sql, companyId, '8999')
    long counterAccountId = resolveAccountId(sql, companyId, closingAccountNumber)
    sql.executeInsert('''
        insert into closing_entry (
            fiscal_year_id, next_fiscal_year_id, voucher_id, entry_type,
            account_id, counter_account_id, amount, created_at
        ) values (?, null, ?, ?, ?, ?, ?, current_timestamp)
    ''', [fiscalYearId, voucherId, RESULT_CLOSING, accountId, counterAccountId, amount])
    1
  }

  private static int persistOpeningBalanceEntries(
      Sql sql,
      long fiscalYearId,
      long nextFiscalYearId,
      long companyId,
      Map<String, BigDecimal> closingBalances
  ) {
    int created = 0
    closingBalances.keySet().sort().each { String accountNumber ->
      BigDecimal amount = scale(closingBalances[accountNumber])
      if (amount == BigDecimal.ZERO) {
        return
      }
      long accountId = resolveAccountId(sql, companyId, accountNumber)
      sql.executeInsert('''
          insert into closing_entry (
              fiscal_year_id,
              next_fiscal_year_id,
              voucher_id,
              entry_type,
              account_id,
              counter_account_id,
              amount,
              created_at
          ) values (?, ?, null, ?, ?, null, ?, current_timestamp)
      ''', [fiscalYearId, nextFiscalYearId, OPENING_BALANCE, accountId, amount])
      created++
    }
    created
  }

  private static void appendUnclassifiedAccountBlocker(Sql sql, long fiscalYearId, List<String> blockers) {
    List<String> unclassifiedAccounts = listUnclassifiedAccountsWithActivity(sql, fiscalYearId)
    if (unclassifiedAccounts.isEmpty()) {
      return
    }
    blockers << ("Följande konton har bokförda transaktioner men saknar kontoklass eller normalsida och måste " +
        "klassificeras i kontoplanen innan bokslut kan göras: ${unclassifiedAccounts.join(', ')}" as String)
  }

  private static List<String> listUnclassifiedAccountsWithActivity(Sql sql, long fiscalYearId) {
    sql.rows('''
        select distinct vl.account_number as accountNumber,
               a.account_name as accountName
          from voucher v
          join voucher_line vl on vl.voucher_id = v.id
          join account a on a.id = vl.account_id
         where v.fiscal_year_id = ?
           and v.status in ('ACTIVE', 'CORRECTION')
           and (a.account_class is null or a.normal_balance_side is null)
         order by vl.account_number
    ''', [fiscalYearId]).collect { GroovyRowResult row ->
      "${row.get('accountNumber')} ${row.get('accountName')}" as String
    }
  }

  private static Map<String, ResultAccountBalance> loadResultBalances(Sql sql, long fiscalYearId) {
    Map<String, ResultAccountBalance> balances = [:]
    sql.rows('''
        select vl.account_number as accountNumber,
               a.account_name as accountName,
               a.account_class as accountClass,
               a.normal_balance_side as normalBalanceSide,
               sum(vl.debit_amount) as debitAmount,
               sum(vl.credit_amount) as creditAmount
          from voucher v
          join voucher_line vl on vl.voucher_id = v.id
          join account a on a.id = vl.account_id
         where v.fiscal_year_id = ?
           and v.status in ('ACTIVE', 'CORRECTION')
           and a.account_class in ('INCOME', 'EXPENSE')
         group by vl.account_number, a.account_name, a.account_class, a.normal_balance_side
    ''', [fiscalYearId]).each { GroovyRowResult row ->
      BigDecimal amount = signedAmount(
          new BigDecimal(row.get('debitAmount').toString()),
          new BigDecimal(row.get('creditAmount').toString()),
          row.get('normalBalanceSide') as String
      )
      if (amount != BigDecimal.ZERO) {
        balances[row.get('accountNumber') as String] = new ResultAccountBalance(
            row.get('accountNumber') as String,
            row.get('accountName') as String,
            row.get('accountClass') as String,
            (row.get('normalBalanceSide') as String)?.trim()?.toUpperCase(Locale.ROOT),
            amount
        )
      }
    }
    balances
  }

  private NextFiscalYearPlan planNextFiscalYear(Sql sql, long companyId, FiscalYear fiscalYear) {
    LocalDate nextStartDate = fiscalYear.endDate.plusDays(1)

    // Reuse an existing year that starts where the next year would start, even when its
    // length differs from the current year (e.g. after a shortened first fiscal year).
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
    ''', [companyId, Date.valueOf(nextStartDate)]) as GroovyRowResult
    if (existing != null) {
      return new NextFiscalYearPlan(mapFiscalYear(existing), false, null)
    }

    LocalDate nextEndDate = nextStartDate.plusYears(1).minusDays(1)
    GroovyRowResult overlap = sql.firstRow('''
        select count(*) as total
          from fiscal_year
         where company_id = ?
           and start_date <= ?
           and end_date >= ?
    ''', [companyId, Date.valueOf(nextEndDate), Date.valueOf(nextStartDate)]) as GroovyRowResult
    if (((Number) overlap.get('total')).intValue() > 0) {
      return new NextFiscalYearPlan(
          null,
          false,
          "Nästa räkenskapsår ${nextStartDate} - ${nextEndDate} överlappar ett befintligt år och kan inte skapas automatiskt."
      )
    }

    String nextName = nextStartDate.year == nextEndDate.year
        ? nextStartDate.year.toString()
        : "${nextStartDate} - ${nextEndDate}"
    new NextFiscalYearPlan(new FiscalYear(null, nextName, nextStartDate, nextEndDate, false, null), true, null)
  }

  private static FiscalYear requireFiscalYear(Sql sql, long fiscalYearId) {
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
    if (row == null) {
      throw new IllegalArgumentException("Okänt räkenskapsår: ${fiscalYearId}")
    }
    mapFiscalYear(row)
  }

  private FiscalYear ensureNextFiscalYear(Sql sql, long companyId, FiscalYear fiscalYear) {
    NextFiscalYearPlan plan = planNextFiscalYear(sql, companyId, fiscalYear)
    if (plan.conflictMessage != null) {
      throw new IllegalStateException(plan.conflictMessage)
    }
    if (!plan.willCreate) {
      return plan.fiscalYear
    }
    FiscalYearService.createFiscalYear(
        sql,
        accountingPeriodService,
        companyId,
        plan.fiscalYear.name,
        plan.fiscalYear.startDate,
        plan.fiscalYear.endDate
    )
  }

  private static boolean hasClosingEntries(Sql sql, long fiscalYearId) {
    GroovyRowResult row = sql.firstRow(
        'select count(*) as total from closing_entry where fiscal_year_id = ?',
        [fiscalYearId]
    ) as GroovyRowResult
    ((Number) row.get('total')).intValue() > 0
  }

  private static BalanceAccountSeed loadAccount(Sql sql, long companyId, String accountNumber) {
    GroovyRowResult row = sql.firstRow('''
        select account_number as accountNumber,
               account_name as accountName,
               account_class as accountClass,
               normal_balance_side as normalBalanceSide,
               active
          from account
         where company_id = ?
           and account_number = ?
    ''', [companyId, accountNumber]) as GroovyRowResult
    if (row == null) {
      return null
    }
    new BalanceAccountSeed(
        row.get('accountNumber') as String,
        row.get('accountName') as String,
        row.get('accountClass') as String,
        (row.get('normalBalanceSide') as String)?.trim()?.toUpperCase(Locale.ROOT),
        Boolean.TRUE == row.get('active')
    )
  }

  private static long resolveAccountId(Sql sql, long companyId, String accountNumber) {
    GroovyRowResult row = sql.firstRow(
        'select id from account where company_id = ? and account_number = ?',
        [companyId, accountNumber]
    ) as GroovyRowResult
    if (row == null) {
      throw new IllegalArgumentException("Kan inte hitta konto ${accountNumber} vid bokslut.")
    }
    ((Number) row.get('id')).longValue()
  }

  private static long resolveCompanyId(Sql sql, long fiscalYearId) {
    CompanyService.resolveFromFiscalYear(sql, fiscalYearId)
  }

  private static String normalizeAccountNumber(String accountNumber) {
    String normalized = accountNumber?.trim()
    if (!(normalized ==~ /\d{4}/)) {
      throw new IllegalArgumentException('Kontonummer måste bestå av fyra siffror.')
    }
    normalized
  }

  private static BigDecimal signedAmount(BigDecimal debitAmount, BigDecimal creditAmount, String normalBalanceSide) {
    String safeNormalBalanceSide = normalBalanceSide?.trim()?.toUpperCase(Locale.ROOT)
    if (!safeNormalBalanceSide) {
      throw new IllegalStateException('Kontot saknar normal balanssida för bokslut.')
    }
    safeNormalBalanceSide == 'DEBIT'
        ? scale(debitAmount - creditAmount)
        : scale(creditAmount - debitAmount)
  }

  private static BigDecimal scale(BigDecimal amount) {
    (amount ?: BigDecimal.ZERO).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)
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

  private static ClosingEntry mapClosingEntry(GroovyRowResult row) {
    new ClosingEntry(
        Long.valueOf(row.get('id').toString()),
        ((Number) row.get('fiscalYearId')).longValue(),
        row.get('nextFiscalYearId') == null ? null : ((Number) row.get('nextFiscalYearId')).longValue(),
        row.get('voucherId') == null ? null : ((Number) row.get('voucherId')).longValue(),
        row.get('entryType') as String,
        row.get('accountId') == null ? null : ((Number) row.get('accountId')).longValue(),
        row.get('counterAccountId') == null ? null : ((Number) row.get('counterAccountId')).longValue(),
        new BigDecimal(row.get('amount').toString()),
        SqlValueMapper.toLocalDateTime(row.get('createdAt'))
    )
  }
}
