package se.alipsa.accounting.service

import groovy.sql.GroovyRowResult
import groovy.sql.Sql

import se.alipsa.accounting.domain.ClosingEntry
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.Voucher
import se.alipsa.accounting.domain.VoucherLine

import java.math.RoundingMode
import java.sql.Date
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Handles year-end closing, retained earnings transfer and next-year opening balances.
 */
final class ClosingService {

  static final String DEFAULT_CLOSING_ACCOUNT = '2099'
  static final String DEFAULT_CLOSING_SERIES = 'YE'
  static final String RESULT_CLOSING = 'RESULT_CLOSING'
  static final String OPENING_BALANCE = 'OPENING_BALANCE'

  private static final int AMOUNT_SCALE = 2

  private final DatabaseService databaseService
  private final AccountingPeriodService accountingPeriodService
  private final FiscalYearService fiscalYearService
  private final VoucherService voucherService
  private final ReportIntegrityService reportIntegrityService
  private final Clock clock

  ClosingService() {
    this(
        DatabaseService.instance,
        new AccountingPeriodService(DatabaseService.instance),
        new FiscalYearService(DatabaseService.instance),
        new VoucherService(DatabaseService.instance),
        new ReportIntegrityService(),
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
    this(databaseService, accountingPeriodService, fiscalYearService, voucherService, reportIntegrityService, Clock.systemDefaultZone())
  }

  ClosingService(
      DatabaseService databaseService,
      AccountingPeriodService accountingPeriodService,
      FiscalYearService fiscalYearService,
      VoucherService voucherService,
      ReportIntegrityService reportIntegrityService,
      Clock clock
  ) {
    this.databaseService = databaseService
    this.accountingPeriodService = accountingPeriodService
    this.fiscalYearService = fiscalYearService
    this.voucherService = voucherService
    this.reportIntegrityService = reportIntegrityService
    this.clock = clock
  }

  YearEndClosingPreview previewClosing(long fiscalYearId, String closingAccountNumber = DEFAULT_CLOSING_ACCOUNT) {
    long companyId = databaseService.withSql { Sql sql ->
      resolveCompanyId(sql, fiscalYearId)
    }
    List<String> integrityProblems = reportIntegrityService.listCriticalProblems(companyId)
    databaseService.withSql { Sql sql ->
      buildPreview(sql, fiscalYearId, closingAccountNumber, integrityProblems)
    }
  }

  YearEndClosingResult closeFiscalYear(long fiscalYearId, String closingAccountNumber = DEFAULT_CLOSING_ACCOUNT) {
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
          preview.resultAccountCount,
          preview.incomeTotal,
          preview.expenseTotal,
          preview.netResult,
          preview.blockingIssues,
          preview.warnings
      )
      ClosingExecution execution = executeClosing(sql, executionPreview)
      FiscalYear closedYear = fiscalYearService.closeFiscalYear(sql, fiscalYearId)
      new YearEndClosingResult(
          closedYear,
          nextFiscalYear,
          execution.closingVoucher,
          executionPreview.resultAccountCount,
          execution.openingBalanceCount,
          execution.closingEntryCount,
          executionPreview.netResult,
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
    String safeClosingAccount = normalizeAccountNumber(closingAccountNumber)
    List<String> blockers = []
    List<String> warnings = []

    if (fiscalYear.closed) {
      blockers << ("Räkenskapsåret ${fiscalYear.name} är redan stängt." as String)
    }
    if (hasClosingEntries(sql, fiscalYear.id)) {
      blockers << ("Räkenskapsåret ${fiscalYear.name} har redan bokslutsposter registrerade." as String)
    }
    if (hasUnlockedPeriods(sql, fiscalYear.id)) {
      blockers << 'Alla perioder måste vara låsta innan årsbokslut kan genomföras.'
    }
    if (!integrityProblems.isEmpty()) {
      String summary = integrityProblems.take(3).join('\n')
      blockers << ("Integritetskontrollerna måste vara gröna före årsstängning:\n${summary}" as String)
    }

    long companyId = resolveCompanyId(sql, fiscalYear.id)
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

    Map<String, ResultAccountBalance> resultBalances = loadResultBalances(sql, fiscalYear.id)
    BigDecimal incomeTotal = resultBalances.values()
        .findAll { ResultAccountBalance balance -> balance.accountClass == 'INCOME' }
        .sum(BigDecimal.ZERO) { ResultAccountBalance balance -> balance.amount } as BigDecimal
    BigDecimal expenseTotal = resultBalances.values()
        .findAll { ResultAccountBalance balance -> balance.accountClass == 'EXPENSE' }
        .sum(BigDecimal.ZERO) { ResultAccountBalance balance -> balance.amount } as BigDecimal
    BigDecimal netResult = scale(incomeTotal - expenseTotal)

    if (resultBalances.isEmpty()) {
      warnings << 'Inga resultatkonton med saldo hittades. Bokslutsverifikationen blir tom och hoppas över.'
    }
    LocalDate dueDate = fiscalYear.endDate.plusMonths(6)
    if (LocalDate.now(clock).isAfter(dueDate)) {
      warnings << ("Bokslutsfristen passerade ${dueDate}. Årsbokslutet borde redan vara upprättat." as String)
    }

    NextFiscalYearPlan nextPlan = planNextFiscalYear(sql, companyId, fiscalYear)
    if (nextPlan.conflictMessage != null) {
      blockers << nextPlan.conflictMessage
    } else if (nextPlan.fiscalYear?.id != null) {
      if (nextPlan.fiscalYear.closed) {
        blockers << ("Nästa räkenskapsår ${nextPlan.fiscalYear.name} är redan stängt." as String)
      } else if (fiscalYearHasActivity(sql, nextPlan.fiscalYear.id)) {
        blockers << ("Nästa räkenskapsår ${nextPlan.fiscalYear.name} innehåller redan ingående balanser eller verifikationer." as String)
      }
    }

    new YearEndClosingPreview(
        fiscalYear,
        nextPlan.fiscalYear,
        nextPlan.willCreate,
        safeClosingAccount,
        resultBalances.size(),
        scale(incomeTotal),
        scale(expenseTotal),
        netResult,
        blockers,
        warnings
    )
  }

  private ClosingExecution executeClosing(Sql sql, YearEndClosingPreview preview) {
    Map<String, ResultAccountBalance> resultBalances = loadResultBalances(sql, preview.fiscalYear.id)
    Voucher closingVoucher = resultBalances.isEmpty()
        ? null
        : createClosingVoucher(sql, preview.fiscalYear, preview.closingAccountNumber, resultBalances)

    long companyId = resolveCompanyId(sql, preview.fiscalYear.id)
    int closingEntryCount = persistResultClosingEntries(
        sql,
        preview.fiscalYear.id,
        closingVoucher?.id,
        preview.closingAccountNumber,
        resultBalances
    )
    Map<String, BigDecimal> closingBalances = loadBalanceClosingBalances(sql, preview.fiscalYear.id)
    int openingBalanceCount = persistOpeningBalances(sql, preview.nextFiscalYear.id, companyId, closingBalances)
    closingEntryCount += persistOpeningBalanceEntries(sql, preview.fiscalYear.id, preview.nextFiscalYear.id, companyId, closingBalances)
    new ClosingExecution(closingVoucher, openingBalanceCount, closingEntryCount)
  }

  private Voucher createClosingVoucher(
      Sql sql,
      FiscalYear fiscalYear,
      String closingAccountNumber,
      Map<String, ResultAccountBalance> resultBalances
  ) {
    List<VoucherLine> lines = buildClosingVoucherLines(resultBalances, closingAccountNumber)
    voucherService.createVoucher(
        sql,
        fiscalYear.id,
        DEFAULT_CLOSING_SERIES,
        fiscalYear.endDate,
        "Årsbokslut ${fiscalYear.name}",
        lines
    )
  }

  private static List<VoucherLine> buildClosingVoucherLines(
      Map<String, ResultAccountBalance> resultBalances,
      String closingAccountNumber
  ) {
    List<VoucherLine> lines = []
    BigDecimal debitTotal = BigDecimal.ZERO.setScale(AMOUNT_SCALE)
    BigDecimal creditTotal = BigDecimal.ZERO.setScale(AMOUNT_SCALE)
    int lineIndex = 1
    resultBalances.keySet().sort().each { String accountNumber ->
      ResultAccountBalance balance = resultBalances[accountNumber]
      BigDecimal amount = scale(balance.amount)
      if (amount == BigDecimal.ZERO) {
        return
      }
      BigDecimal debitAmount = BigDecimal.ZERO.setScale(AMOUNT_SCALE)
      BigDecimal creditAmount = BigDecimal.ZERO.setScale(AMOUNT_SCALE)
      if (amount > BigDecimal.ZERO) {
        if (balance.normalBalanceSide == 'DEBIT') {
          creditAmount = amount
        } else {
          debitAmount = amount
        }
      } else {
        BigDecimal absolute = scale(amount.abs())
        if (balance.normalBalanceSide == 'DEBIT') {
          debitAmount = absolute
        } else {
          creditAmount = absolute
        }
      }
      debitTotal = scale(debitTotal + debitAmount)
      creditTotal = scale(creditTotal + creditAmount)
      lines << new VoucherLine(
          null,
          null,
          lineIndex++,
          null,
          balance.accountNumber,
          balance.accountName,
          "Årsbokslut ${balance.accountNumber}",
          debitAmount,
          creditAmount
      )
    }

    BigDecimal difference = scale(debitTotal - creditTotal)
    if (difference != BigDecimal.ZERO) {
      lines << new VoucherLine(
          null,
          null,
          lineIndex,
          null,
          closingAccountNumber,
          null,
          "Årets resultat ${closingAccountNumber}",
          difference < BigDecimal.ZERO ? difference.abs() : BigDecimal.ZERO.setScale(AMOUNT_SCALE),
          difference > BigDecimal.ZERO ? difference : BigDecimal.ZERO.setScale(AMOUNT_SCALE)
      )
    }
    lines
  }

  private static int persistResultClosingEntries(
      Sql sql,
      long fiscalYearId,
      Long voucherId,
      String closingAccountNumber,
      Map<String, ResultAccountBalance> resultBalances
  ) {
    int created = 0
    long companyId = resolveCompanyId(sql, fiscalYearId)
    long counterAccountId = resolveAccountId(sql, companyId, closingAccountNumber)
    resultBalances.keySet().sort().each { String accountNumber ->
      ResultAccountBalance balance = resultBalances[accountNumber]
      BigDecimal amount = scale(balance.amount)
      if (amount == BigDecimal.ZERO) {
        return
      }
      long accountId = resolveAccountId(sql, companyId, balance.accountNumber)
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
          ) values (?, null, ?, ?, ?, ?, ?, current_timestamp)
      ''', [fiscalYearId, voucherId, RESULT_CLOSING, accountId, counterAccountId, amount])
      created++
    }
    created
  }

  private static int persistOpeningBalances(
      Sql sql,
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
          insert into opening_balance (
              fiscal_year_id,
              account_id,
              amount,
              created_at,
              updated_at
          ) values (?, ?, ?, current_timestamp, current_timestamp)
      ''', [nextFiscalYearId, accountId, amount])
      created++
    }
    created
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

  private static Map<String, BigDecimal> loadBalanceClosingBalances(Sql sql, long fiscalYearId) {
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

    Map<String, BigDecimal> closingBalances = [:]
    accounts.each { String accountNumber ->
      closingBalances[accountNumber] = scale((openings[accountNumber] ?: BigDecimal.ZERO) + (movements[accountNumber] ?: BigDecimal.ZERO))
    }
    closingBalances
  }

  private NextFiscalYearPlan planNextFiscalYear(Sql sql, long companyId, FiscalYear fiscalYear) {
    LocalDate nextStartDate = fiscalYear.endDate.plusDays(1)
    long daySpan = ChronoUnit.DAYS.between(fiscalYear.startDate, fiscalYear.endDate)
    LocalDate nextEndDate = nextStartDate.plusDays(daySpan)

    GroovyRowResult exact = sql.firstRow('''
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
    ''', [companyId, Date.valueOf(nextStartDate), Date.valueOf(nextEndDate)]) as GroovyRowResult
    if (exact != null) {
      return new NextFiscalYearPlan(mapFiscalYear(exact), false, null)
    }

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

  private static boolean hasUnlockedPeriods(Sql sql, long fiscalYearId) {
    GroovyRowResult row = sql.firstRow('''
        select count(*) as total
          from accounting_period
         where fiscal_year_id = ?
           and locked = false
    ''', [fiscalYearId]) as GroovyRowResult
    ((Number) row.get('total')).intValue() > 0
  }

  private static boolean fiscalYearHasActivity(Sql sql, long fiscalYearId) {
    GroovyRowResult voucherRow = sql.firstRow(
        'select count(*) as total from voucher where fiscal_year_id = ?',
        [fiscalYearId]
    ) as GroovyRowResult
    GroovyRowResult openingRow = sql.firstRow(
        'select count(*) as total from opening_balance where fiscal_year_id = ?',
        [fiscalYearId]
    ) as GroovyRowResult
    ((Number) voucherRow.get('total')).intValue() > 0 || ((Number) openingRow.get('total')).intValue() > 0
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
