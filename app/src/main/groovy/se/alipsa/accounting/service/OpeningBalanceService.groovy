package se.alipsa.accounting.service

import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.transform.Canonical
import groovy.transform.PackageScope

import se.alipsa.accounting.domain.FiscalYear

import java.math.RoundingMode
import java.sql.Date

/**
 * Reads, transfers, refreshes, and manually maintains fiscal-year opening balances.
 */
final class OpeningBalanceService {

  static final String ORIGIN_MANUAL = 'MANUAL'
  static final String ORIGIN_TRANSFERRED = 'TRANSFERRED'
  static final String ORIGIN_YEAR_END_CLOSE = 'YEAR_END_CLOSE'

  private static final Set<String> AUTO_MANAGED_ORIGINS = [
      ORIGIN_TRANSFERRED,
      ORIGIN_YEAR_END_CLOSE
  ] as Set<String>

  private final DatabaseService databaseService
  private final AccountService accountService

  OpeningBalanceService(DatabaseService databaseService = DatabaseService.instance) {
    this.databaseService = databaseService
    this.accountService = new AccountService(databaseService)
  }

  List<OpeningBalanceLine> listForFiscalYear(long companyId, long fiscalYearId) {
    CompanyService.requireValidCompanyId(companyId)
    databaseService.withSql { Sql sql ->
      requireFiscalYearBelongsToCompany(sql, companyId, fiscalYearId)
      loadLines(sql, companyId, fiscalYearId)
    }
  }

  FiscalYear findImmediatePreviousFiscalYear(long companyId, long fiscalYearId) {
    CompanyService.requireValidCompanyId(companyId)
    databaseService.withSql { Sql sql ->
      requireFiscalYearBelongsToCompany(sql, companyId, fiscalYearId)
      findImmediatePreviousFiscalYear(sql, companyId, fiscalYearId)
    }
  }

  FiscalYear findAutoManagedSourceFiscalYear(long fiscalYearId) {
    databaseService.withSql { Sql sql ->
      Long sourceFiscalYearId = findAutoManagedSourceFiscalYearId(sql, fiscalYearId)
      sourceFiscalYearId == null ? null : requireFiscalYear(sql, sourceFiscalYearId)
    }
  }

  boolean hasVoucherActivity(long fiscalYearId) {
    databaseService.withSql { Sql sql ->
      hasVoucherActivity(sql, fiscalYearId)
    }
  }

  void saveManualOpeningBalance(long fiscalYearId, String accountNumber, BigDecimal amount) {
    accountService.saveOpeningBalance(fiscalYearId, accountNumber, amount)
  }

  List<OpeningBalanceDrift> detectDrift(long fiscalYearId) {
    databaseService.withSql { Sql sql ->
      detectDrift(sql, fiscalYearId)
    }
  }

  int transferFromPreviousFiscalYear(long sourceFiscalYearId, long targetFiscalYearId) {
    databaseService.withTransaction { Sql sql ->
      long sourceCompanyId = resolveCompanyId(sql, sourceFiscalYearId)
      long targetCompanyId = resolveCompanyId(sql, targetFiscalYearId)
      if (sourceCompanyId != targetCompanyId) {
        throw new IllegalArgumentException('Fiscal years must belong to the same company.')
      }
      Map<String, BigDecimal> balances = loadBalanceClosingBalances(sql, sourceFiscalYearId)
      replaceTransferredBalances(sql, sourceFiscalYearId, targetFiscalYearId, balances)
    }
  }

  int refreshTransferredBalances(long fiscalYearId) {
    databaseService.withTransaction { Sql sql ->
      Long sourceFiscalYearId = findAutoManagedSourceFiscalYearId(sql, fiscalYearId)
      if (sourceFiscalYearId == null) {
        return 0
      }
      Map<String, BigDecimal> balances = loadBalanceClosingBalances(sql, sourceFiscalYearId)
      replaceTransferredBalances(sql, sourceFiscalYearId, fiscalYearId, balances)
    }
  }

  @PackageScope
  List<OpeningBalanceDrift> detectDrift(Sql sql, long fiscalYearId) {
    List<StoredOpeningBalance> storedRows = loadStoredOpeningBalances(sql, fiscalYearId)
    List<StoredOpeningBalance> autoRows = storedRows.findAll { StoredOpeningBalance row ->
      isAutoManaged(row.originType) && row.sourceFiscalYearId != null
    }
    if (autoRows.isEmpty()) {
      return []
    }

    Set<String> manualAccounts = storedRows.findAll { StoredOpeningBalance row ->
      row.originType == ORIGIN_MANUAL
    }.collect { StoredOpeningBalance row -> row.accountNumber } as Set<String>

    Map<Long, Map<String, BigDecimal>> expectedBySource = [:]
    autoRows.collect { StoredOpeningBalance row -> row.sourceFiscalYearId }.unique().each { Long sourceFiscalYearId ->
      expectedBySource[sourceFiscalYearId] = loadBalanceClosingBalances(sql, sourceFiscalYearId)
    }

    Map<String, StoredOpeningBalance> autoByAccount = [:]
    autoRows.each { StoredOpeningBalance row ->
      autoByAccount[row.accountNumber] = row
    }

    List<OpeningBalanceDrift> drift = []
    autoRows.each { StoredOpeningBalance row ->
      BigDecimal expected = scale((expectedBySource[row.sourceFiscalYearId] ?: [:])[row.accountNumber] ?: BigDecimal.ZERO)
      if (scale(row.amount) != expected) {
        drift << new OpeningBalanceDrift(
            row.accountNumber,
            row.accountName,
            scale(row.amount),
            expected,
            row.originType,
            row.sourceFiscalYearId
        )
      }
    }

    expectedBySource.each { Long sourceFiscalYearId, Map<String, BigDecimal> expected ->
      expected.each { String accountNumber, BigDecimal expectedAmount ->
        if (expectedAmount == BigDecimal.ZERO || autoByAccount.containsKey(accountNumber) || manualAccounts.contains(accountNumber)) {
          return
        }
        String accountName = lookupAccountName(sql, resolveCompanyId(sql, fiscalYearId), accountNumber)
        drift << new OpeningBalanceDrift(
            accountNumber,
            accountName,
            null,
            expectedAmount,
            ORIGIN_TRANSFERRED,
            sourceFiscalYearId
        )
      }
    }
    drift.sort { OpeningBalanceDrift row -> row.accountNumber }
  }

  @PackageScope
  int replaceTransferredBalances(Sql sql, long sourceFiscalYearId, long targetFiscalYearId, Map<String, BigDecimal> balances) {
    long sourceCompanyId = resolveCompanyId(sql, sourceFiscalYearId)
    long targetCompanyId = resolveCompanyId(sql, targetFiscalYearId)
    if (sourceCompanyId != targetCompanyId) {
      throw new IllegalArgumentException('Fiscal years must belong to the same company.')
    }
    Set<String> manualAccounts = manualAccounts(sql, targetFiscalYearId)
    deleteAutoManagedRows(sql, targetFiscalYearId)
    insertBalances(sql, targetCompanyId, targetFiscalYearId, sourceFiscalYearId, balances, ORIGIN_TRANSFERRED, manualAccounts)
  }

  @PackageScope
  int replaceYearEndClosingBalances(Sql sql, long sourceFiscalYearId, long targetFiscalYearId, Map<String, BigDecimal> balances) {
    if (hasVoucherActivity(sql, targetFiscalYearId)) {
      throw new IllegalStateException('Next fiscal year already contains vouchers.')
    }
    if (hasManualOpeningBalances(sql, targetFiscalYearId)) {
      throw new IllegalStateException('Next fiscal year already contains manual opening balances.')
    }
    long targetCompanyId = resolveCompanyId(sql, targetFiscalYearId)
    sql.executeUpdate('delete from opening_balance where fiscal_year_id = ?', [targetFiscalYearId])
    insertBalances(sql, targetCompanyId, targetFiscalYearId, sourceFiscalYearId, balances, ORIGIN_YEAR_END_CLOSE, [] as Set<String>)
  }

  @PackageScope
  boolean hasManualOpeningBalances(Sql sql, long fiscalYearId) {
    GroovyRowResult row = sql.firstRow(
        'select count(*) as total from opening_balance where fiscal_year_id = ? and origin_type = ?',
        [fiscalYearId, ORIGIN_MANUAL]
    ) as GroovyRowResult
    ((Number) row.get('total')).intValue() > 0
  }

  @PackageScope
  boolean hasAutoManagedOpeningBalances(Sql sql, long fiscalYearId) {
    GroovyRowResult row = sql.firstRow(
        'select count(*) as total from opening_balance where fiscal_year_id = ? and origin_type in (?, ?)',
        [fiscalYearId, ORIGIN_TRANSFERRED, ORIGIN_YEAR_END_CLOSE]
    ) as GroovyRowResult
    ((Number) row.get('total')).intValue() > 0
  }

  @PackageScope
  boolean hasVoucherActivity(Sql sql, long fiscalYearId) {
    GroovyRowResult row = sql.firstRow(
        'select count(*) as total from voucher where fiscal_year_id = ? and status in (\'ACTIVE\', \'CORRECTION\')',
        [fiscalYearId]
    ) as GroovyRowResult
    ((Number) row.get('total')).intValue() > 0
  }

  @PackageScope
  Map<String, BigDecimal> loadBalanceClosingBalances(Sql sql, long fiscalYearId) {
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

  private List<OpeningBalanceLine> loadLines(Sql sql, long companyId, long fiscalYearId) {
    sql.rows('''
        select a.account_number as accountNumber,
               a.account_name as accountName,
               a.account_class as accountClass,
               coalesce(ob.amount, 0) as amount,
               ob.origin_type as originType,
               ob.source_fiscal_year_id as sourceFiscalYearId
          from account a
          left join opening_balance ob
            on ob.account_id = a.id
           and ob.fiscal_year_id = ?
         where a.company_id = ?
           and a.account_class in ('ASSET', 'LIABILITY', 'EQUITY')
         order by a.account_number
    ''', [fiscalYearId, companyId]).collect { GroovyRowResult row ->
      new OpeningBalanceLine(
          row.get('accountNumber') as String,
          row.get('accountName') as String,
          row.get('accountClass') as String,
          scale(new BigDecimal(row.get('amount').toString())),
          (row.get('originType') as String) ?: ORIGIN_MANUAL,
          row.get('sourceFiscalYearId') as Long
      )
    }
  }

  private List<StoredOpeningBalance> loadStoredOpeningBalances(Sql sql, long fiscalYearId) {
    sql.rows('''
        select a.account_number as accountNumber,
               a.account_name as accountName,
               ob.amount,
               ob.origin_type as originType,
               ob.source_fiscal_year_id as sourceFiscalYearId
          from opening_balance ob
          join account a on a.id = ob.account_id
         where ob.fiscal_year_id = ?
    ''', [fiscalYearId]).collect { GroovyRowResult row ->
      new StoredOpeningBalance(
          row.get('accountNumber') as String,
          row.get('accountName') as String,
          scale(new BigDecimal(row.get('amount').toString())),
          row.get('originType') as String,
          row.get('sourceFiscalYearId') as Long
      )
    }
  }

  private static Set<String> manualAccounts(Sql sql, long fiscalYearId) {
    sql.rows('''
        select a.account_number as accountNumber
          from opening_balance ob
          join account a on a.id = ob.account_id
         where ob.fiscal_year_id = ?
           and ob.origin_type = ?
    ''', [fiscalYearId, ORIGIN_MANUAL]).collect { GroovyRowResult row ->
      row.get('accountNumber') as String
    } as Set<String>
  }

  private static void deleteAutoManagedRows(Sql sql, long fiscalYearId) {
    sql.executeUpdate(
        'delete from opening_balance where fiscal_year_id = ? and origin_type in (?, ?)',
        [fiscalYearId, ORIGIN_TRANSFERRED, ORIGIN_YEAR_END_CLOSE]
    )
  }

  private static int insertBalances(
      Sql sql,
      long companyId,
      long fiscalYearId,
      long sourceFiscalYearId,
      Map<String, BigDecimal> balances,
      String originType,
      Set<String> protectedAccounts
  ) {
    int created = 0
    balances.keySet().sort().each { String accountNumber ->
      BigDecimal amount = scale(balances[accountNumber] ?: BigDecimal.ZERO)
      if (amount == BigDecimal.ZERO || protectedAccounts.contains(accountNumber)) {
        return
      }
      long accountId = resolveAccountId(sql, companyId, accountNumber)
      sql.executeInsert('''
          insert into opening_balance (
              fiscal_year_id,
              account_id,
              amount,
              origin_type,
              source_fiscal_year_id,
              created_at,
              updated_at
          ) values (?, ?, ?, ?, ?, current_timestamp, current_timestamp)
      ''', [fiscalYearId, accountId, amount, originType, sourceFiscalYearId])
      created++
    }
    created
  }

  private static FiscalYear findImmediatePreviousFiscalYear(Sql sql, long companyId, long fiscalYearId) {
    FiscalYear target = requireFiscalYear(sql, fiscalYearId)
    GroovyRowResult row = sql.firstRow('''
        select id,
               name,
               start_date as startDate,
               end_date as endDate,
               closed,
               closed_at as closedAt
          from fiscal_year
         where company_id = ?
           and end_date < ?
         order by end_date desc
         fetch first 1 row only
    ''', [companyId, Date.valueOf(target.startDate)]) as GroovyRowResult
    row == null ? null : mapFiscalYear(row)
  }

  private static Long findAutoManagedSourceFiscalYearId(Sql sql, long fiscalYearId) {
    List<Long> sourceIds = sql.rows('''
        select distinct source_fiscal_year_id as sourceFiscalYearId
          from opening_balance
         where fiscal_year_id = ?
           and origin_type in (?, ?)
           and source_fiscal_year_id is not null
    ''', [fiscalYearId, ORIGIN_TRANSFERRED, ORIGIN_YEAR_END_CLOSE]).collect { GroovyRowResult row ->
      row.get('sourceFiscalYearId') as Long
    }
    if (sourceIds.isEmpty()) {
      return null
    }
    if (sourceIds.size() > 1) {
      throw new IllegalStateException("Opening balances for fiscal year ${fiscalYearId} refer to multiple source fiscal years.")
    }
    sourceIds.first()
  }

  private static long resolveAccountId(Sql sql, long companyId, String accountNumber) {
    GroovyRowResult row = sql.firstRow(
        'select id from account where company_id = ? and account_number = ?',
        [companyId, accountNumber]
    ) as GroovyRowResult
    if (row == null) {
      throw new IllegalArgumentException("Unknown account number: ${accountNumber}")
    }
    ((Number) row.get('id')).longValue()
  }

  private static String lookupAccountName(Sql sql, long companyId, String accountNumber) {
    GroovyRowResult row = sql.firstRow(
        'select account_name as accountName from account where company_id = ? and account_number = ?',
        [companyId, accountNumber]
    ) as GroovyRowResult
    row?.get('accountName') as String
  }

  private static void requireFiscalYearBelongsToCompany(Sql sql, long companyId, long fiscalYearId) {
    long actualCompanyId = resolveCompanyId(sql, fiscalYearId)
    if (actualCompanyId != companyId) {
      throw new IllegalArgumentException("Fiscal year ${fiscalYearId} does not belong to company ${companyId}.")
    }
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
      throw new IllegalArgumentException("Unknown fiscal year id: ${fiscalYearId}")
    }
    mapFiscalYear(row)
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

  private static long resolveCompanyId(Sql sql, long fiscalYearId) {
    CompanyService.resolveFromFiscalYear(sql, fiscalYearId)
  }

  private static boolean isAutoManaged(String originType) {
    AUTO_MANAGED_ORIGINS.contains(originType)
  }

  private static BigDecimal signedAmount(BigDecimal debitAmount, BigDecimal creditAmount, String normalBalanceSide) {
    normalBalanceSide == 'CREDIT'
        ? scale((creditAmount ?: BigDecimal.ZERO) - (debitAmount ?: BigDecimal.ZERO))
        : scale((debitAmount ?: BigDecimal.ZERO) - (creditAmount ?: BigDecimal.ZERO))
  }

  private static BigDecimal scale(BigDecimal amount) {
    (amount ?: BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)
  }

  @Canonical
  static final class OpeningBalanceLine {
    String accountNumber
    String accountName
    String accountClass
    BigDecimal amount
    String originType
    Long sourceFiscalYearId
  }

  @Canonical
  static final class OpeningBalanceDrift {
    String accountNumber
    String accountName
    BigDecimal currentAmount
    BigDecimal expectedAmount
    String originType
    Long sourceFiscalYearId
  }

  @Canonical
  private static final class StoredOpeningBalance {
    String accountNumber
    String accountName
    BigDecimal amount
    String originType
    Long sourceFiscalYearId
  }
}
