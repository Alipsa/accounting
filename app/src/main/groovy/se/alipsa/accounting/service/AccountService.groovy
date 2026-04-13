package se.alipsa.accounting.service

import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.transform.Canonical

import se.alipsa.accounting.domain.Account
import se.alipsa.accounting.domain.OpeningBalance

import java.math.RoundingMode

/**
 * Queries and updates chart of accounts data and opening balances.
 */
final class AccountService {

  private final DatabaseService databaseService

  AccountService(DatabaseService databaseService = DatabaseService.instance) {
    this.databaseService = databaseService
  }

  boolean hasAccounts() {
    databaseService.withSql { Sql sql ->
      GroovyRowResult row = sql.firstRow('select count(*) as total from account') as GroovyRowResult
      ((Number) row.get('total')).intValue() > 0
    }
  }

  AccountOverview loadOverview() {
    databaseService.withSql { Sql sql ->
      GroovyRowResult row = sql.firstRow('''
          select count(*) as total,
                 coalesce(sum(case when active then 1 else 0 end), 0) as activeCount,
                 coalesce(sum(case when manual_review_required then 1 else 0 end), 0) as manualReviewCount
            from account
      ''') as GroovyRowResult
      new AccountOverview(
          ((Number) row.get('total')).intValue(),
          ((Number) row.get('activeCount')).intValue(),
          ((Number) row.get('manualReviewCount')).intValue()
      )
    }
  }

  List<Account> searchAccounts(String queryText, String classFilter, boolean activeOnly, boolean manualReviewOnly) {
    databaseService.withSql { Sql sql ->
      StringBuilder query = new StringBuilder('''
          select account_number as accountNumber,
                 account_name as accountName,
                 account_class as accountClass,
                 normal_balance_side as normalBalanceSide,
                 vat_code as vatCode,
                 active,
                 manual_review_required as manualReviewRequired,
                 classification_note as classificationNote
            from account
           where 1 = 1
      ''')
      List<Object> params = []

      String normalizedQuery = queryText?.trim()?.toLowerCase(Locale.ROOT)
      if (normalizedQuery) {
        query.append(' and (lower(account_number) like ? or lower(account_name) like ?)')
        String pattern = "%${normalizedQuery}%"
        params << pattern
        params << pattern
      }

      if (classFilter?.trim()) {
        query.append(' and account_class = ?')
        params << classFilter.trim()
      }
      if (activeOnly) {
        query.append(' and active = true')
      }
      if (manualReviewOnly) {
        query.append(' and manual_review_required = true')
      }

      query.append(' order by account_number')
      sql.rows(query.toString(), params).collect { GroovyRowResult row ->
        mapAccount(row)
      }
    }
  }

  Account findAccount(String accountNumber) {
    String normalized = normalizeAccountNumber(accountNumber)
    databaseService.withSql { Sql sql ->
      GroovyRowResult row = sql.firstRow('''
          select account_number as accountNumber,
                 account_name as accountName,
                 account_class as accountClass,
                 normal_balance_side as normalBalanceSide,
                 vat_code as vatCode,
                 active,
                 manual_review_required as manualReviewRequired,
                 classification_note as classificationNote
            from account
           where account_number = ?
      ''', [normalized]) as GroovyRowResult
      row == null ? null : mapAccount(row)
    }
  }

  void setAccountActive(String accountNumber, boolean active) {
    String normalized = normalizeAccountNumber(accountNumber)
    databaseService.withTransaction { Sql sql ->
      int updated = sql.executeUpdate('''
          update account
             set active = ?,
                 updated_at = current_timestamp
           where account_number = ?
      ''', [active, normalized])
      if (updated != 1) {
        throw new IllegalArgumentException("Unknown account number: ${normalized}")
      }
    }
  }

  OpeningBalance getOpeningBalance(long fiscalYearId, String accountNumber) {
    String normalized = normalizeAccountNumber(accountNumber)
    databaseService.withSql { Sql sql ->
      GroovyRowResult row = sql.firstRow('''
          select fiscal_year_id as fiscalYearId,
                 account_number as accountNumber,
                 amount
            from opening_balance
           where fiscal_year_id = ?
             and account_number = ?
      ''', [fiscalYearId, normalized]) as GroovyRowResult
      row == null ? new OpeningBalance(fiscalYearId, normalized, BigDecimal.ZERO) : mapOpeningBalance(row)
    }
  }

  OpeningBalance saveOpeningBalance(long fiscalYearId, String accountNumber, BigDecimal amount) {
    String normalizedAccountNumber = normalizeAccountNumber(accountNumber)
    BigDecimal normalizedAmount = normalizeAmount(amount)
    databaseService.withTransaction { Sql sql ->
      Account account = requireBalanceAccount(sql, normalizedAccountNumber)
      requireFiscalYear(sql, fiscalYearId)
      boolean exists = openingBalanceExists(sql, fiscalYearId, normalizedAccountNumber)
      if (exists) {
        sql.executeUpdate('''
            update opening_balance
               set amount = ?,
                   updated_at = current_timestamp
             where fiscal_year_id = ?
               and account_number = ?
        ''', [normalizedAmount, fiscalYearId, normalizedAccountNumber])
      } else {
        sql.executeInsert('''
            insert into opening_balance (
                fiscal_year_id,
                account_number,
                amount,
                created_at,
                updated_at
            ) values (?, ?, ?, current_timestamp, current_timestamp)
        ''', [fiscalYearId, normalizedAccountNumber, normalizedAmount])
      }
      new OpeningBalance(fiscalYearId, account.accountNumber, normalizedAmount)
    }
  }

  private static String normalizeAccountNumber(String accountNumber) {
    String normalized = accountNumber?.trim()
    if (!(normalized ==~ /\d{4}/)) {
      throw new IllegalArgumentException('Account number must consist of four digits.')
    }
    normalized
  }

  private static BigDecimal normalizeAmount(BigDecimal amount) {
    if (amount == null) {
      throw new IllegalArgumentException('Opening balance amount is required.')
    }
    amount.setScale(2, RoundingMode.HALF_UP)
  }

  private static boolean openingBalanceExists(Sql sql, long fiscalYearId, String accountNumber) {
    GroovyRowResult row = sql.firstRow(
        'select count(*) as total from opening_balance where fiscal_year_id = ? and account_number = ?',
        [fiscalYearId, accountNumber]
    ) as GroovyRowResult
    ((Number) row.get('total')).intValue() == 1
  }

  private static void requireFiscalYear(Sql sql, long fiscalYearId) {
    GroovyRowResult row = sql.firstRow(
        'select count(*) as total from fiscal_year where id = ?',
        [fiscalYearId]
    ) as GroovyRowResult
    if (((Number) row.get('total')).intValue() != 1) {
      throw new IllegalArgumentException("Unknown fiscal year id: ${fiscalYearId}")
    }
  }

  private static Account requireBalanceAccount(Sql sql, String accountNumber) {
    GroovyRowResult row = sql.firstRow('''
        select account_number as accountNumber,
               account_name as accountName,
               account_class as accountClass,
               normal_balance_side as normalBalanceSide,
               vat_code as vatCode,
               active,
               manual_review_required as manualReviewRequired,
               classification_note as classificationNote
          from account
         where account_number = ?
    ''', [accountNumber]) as GroovyRowResult
    if (row == null) {
      throw new IllegalArgumentException("Unknown account number: ${accountNumber}")
    }
    Account account = mapAccount(row)
    if (!account.isBalanceAccount()) {
      throw new IllegalArgumentException("Opening balances may only be stored on balance accounts: ${accountNumber}")
    }
    account
  }

  private static Account mapAccount(GroovyRowResult row) {
    new Account(
        row.get('accountNumber') as String,
        row.get('accountName') as String,
        row.get('accountClass') as String,
        row.get('normalBalanceSide') as String,
        row.get('vatCode') as String,
        Boolean.TRUE == row.get('active'),
        Boolean.TRUE == row.get('manualReviewRequired'),
        row.get('classificationNote') as String
    )
  }

  private static OpeningBalance mapOpeningBalance(GroovyRowResult row) {
    new OpeningBalance(
        Long.valueOf(row.get('fiscalYearId').toString()),
        row.get('accountNumber') as String,
        new BigDecimal(row.get('amount').toString())
    )
  }

  @Canonical
  static final class AccountOverview {

    int totalCount
    int activeCount
    int manualReviewCount
  }
}
