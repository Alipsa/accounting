package se.alipsa.accounting.service

import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.transform.Canonical
import groovy.transform.PackageScope

import se.alipsa.accounting.domain.Account
import se.alipsa.accounting.domain.OpeningBalance
import se.alipsa.accounting.domain.VatCode
import se.alipsa.accounting.domain.Voucher

import java.math.RoundingMode

/**
 * Queries and updates chart of accounts data and opening balances.
 */
final class AccountService {

  @PackageScope
  static final String UNKNOWN_ACCOUNT_MESSAGE_PREFIX = 'Okänt kontonummer:'

  @PackageScope
  static final String ACCOUNT_CLASS_INCOME = 'INCOME'

  @PackageScope
  static final String ACCOUNT_CLASS_EXPENSE = 'EXPENSE'

  @PackageScope
  static final String ACCOUNT_CLASS_ASSET = 'ASSET'

  @PackageScope
  static final String ACCOUNT_CLASS_LIABILITY = 'LIABILITY'

  private final DatabaseService databaseService

  AccountService(DatabaseService databaseService = DatabaseService.instance) {
    this.databaseService = databaseService
  }

  boolean hasAccounts(long companyId) {
    CompanyService.requireValidCompanyId(companyId)
    databaseService.withSql { Sql sql ->
      GroovyRowResult row = sql.firstRow(
          'select count(*) as total from account where company_id = ?',
          [companyId]
      ) as GroovyRowResult
      ((Number) row.get('total')).intValue() > 0
    }
  }

  AccountOverview loadOverview(long companyId) {
    CompanyService.requireValidCompanyId(companyId)
    databaseService.withSql { Sql sql ->
      GroovyRowResult row = sql.firstRow('''
          select count(*) as total,
                 coalesce(sum(case when active then 1 else 0 end), 0) as activeCount,
                 coalesce(sum(case when manual_review_required then 1 else 0 end), 0) as manualReviewCount
            from account
           where company_id = ?
      ''', [companyId]) as GroovyRowResult
      new AccountOverview(
          ((Number) row.get('total')).intValue(),
          ((Number) row.get('activeCount')).intValue(),
          ((Number) row.get('manualReviewCount')).intValue()
      )
    }
  }

  List<Account> searchAccounts(long companyId, String queryText, String classFilter, boolean activeOnly, boolean manualReviewOnly) {
    CompanyService.requireValidCompanyId(companyId)
    databaseService.withSql { Sql sql ->
      StringBuilder query = new StringBuilder('''
          select id,
                 company_id as companyId,
                 account_number as accountNumber,
                 account_name as accountName,
                 account_class as accountClass,
                 normal_balance_side as normalBalanceSide,
                 vat_code as vatCode,
                 active,
                 manual_review_required as manualReviewRequired,
                 classification_note as classificationNote,
                 account_subgroup as accountSubgroup
            from account
           where company_id = ?
      ''')
      List<Object> params = [companyId]

      String normalizedQuery = queryText?.trim()?.toLowerCase(Locale.ROOT)
      if (normalizedQuery) {
        query.append(' and (lower(account_number) like ? or lower(account_name) like ?)')
        String numberPattern = normalizedQuery.matches('[0-9]+')
            ? "${normalizedQuery}%" as String
            : "%${normalizedQuery}%" as String
        String namePattern = "%${normalizedQuery}%"
        params << numberPattern
        params << namePattern
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

  Account findAccount(long companyId, String accountNumber) {
    CompanyService.requireValidCompanyId(companyId)
    String normalized = normalizeAccountNumber(accountNumber)
    databaseService.withSql { Sql sql ->
      GroovyRowResult row = sql.firstRow('''
          select id,
                 company_id as companyId,
                 account_number as accountNumber,
                 account_name as accountName,
                 account_class as accountClass,
                 normal_balance_side as normalBalanceSide,
                 vat_code as vatCode,
                 active,
                 manual_review_required as manualReviewRequired,
                 classification_note as classificationNote,
                 account_subgroup as accountSubgroup
            from account
           where company_id = ?
             and account_number = ?
      ''', [companyId, normalized]) as GroovyRowResult
      row == null ? null : mapAccount(row)
    }
  }

  void setAccountActive(long companyId, String accountNumber, boolean active) {
    CompanyService.requireValidCompanyId(companyId)
    String normalized = normalizeAccountNumber(accountNumber)
    databaseService.withTransaction { Sql sql ->
      int updated = sql.executeUpdate('''
          update account
             set active = ?,
                 updated_at = current_timestamp
           where company_id = ?
             and account_number = ?
      ''', [active, companyId, normalized])
      if (updated != 1) {
        throw new IllegalArgumentException("${UNKNOWN_ACCOUNT_MESSAGE_PREFIX} ${normalized}")
      }
    }
  }

  void setAccountVatCode(long companyId, String accountNumber, VatCode vatCode) {
    CompanyService.requireValidCompanyId(companyId)
    String normalized = normalizeAccountNumber(accountNumber)
    databaseService.withTransaction { Sql sql ->
      Account account = requireAccount(sql, companyId, normalized)
      if (vatCode != null && !isVatCompatible(account, vatCode)) {
        throw new IllegalArgumentException(
            "Konto ${normalized} med kontoklass ${account.accountClass} är inte kompatibelt med momskod ${vatCode.name()}."
        )
      }
      int updated = sql.executeUpdate('''
          update account
             set vat_code = ?,
                 updated_at = current_timestamp
           where company_id = ?
             and account_number = ?
      ''', [vatCode?.name(), companyId, normalized])
      if (updated != 1) {
        throw new IllegalStateException("Konto ${normalized} kunde inte uppdateras med momskod.")
      }
    }
  }

  private static final Set<String> VAT_COMPATIBLE_CLASSES = [
      ACCOUNT_CLASS_INCOME,
      ACCOUNT_CLASS_EXPENSE,
      ACCOUNT_CLASS_ASSET,
      ACCOUNT_CLASS_LIABILITY
  ] as Set<String>

  private static final Set<VatCode> INCOME_VAT_CODES = [
      VatCode.OUTPUT_25,
      VatCode.OUTPUT_12,
      VatCode.OUTPUT_6,
      VatCode.EU_SUPPLY_GOODS,
      VatCode.EU_SUPPLY_SERVICES,
      VatCode.EXEMPT,
      VatCode.OUTSIDE_SCOPE
  ] as Set<VatCode>

  private static final Set<VatCode> ASSET_VAT_CODES = [
      VatCode.INPUT_25,
      VatCode.INPUT_12,
      VatCode.INPUT_6,
      VatCode.REVERSE_CHARGE_DOMESTIC,
      VatCode.EU_ACQUISITION_GOODS,
      VatCode.EU_ACQUISITION_SERVICES,
      VatCode.EXEMPT,
      VatCode.OUTSIDE_SCOPE
  ] as Set<VatCode>

  // Returns true for deductible input-side VAT codes (INPUT_*, reverse-charge base codes, EXEMPT,
  // OUTSIDE_SCOPE) — the codes valid on ASSET and EXPENSE accounts. Does not include output-side
  // codes like REVERSE_CHARGE_EU_25, which despite its name goes on a LIABILITY account.
  @PackageScope
  static boolean isInputSideVatCode(VatCode vatCode) {
    vatCode in ASSET_VAT_CODES
  }

  // Expense accounts accept the same input-side VAT codes as asset accounts.
  private static final Set<VatCode> EXPENSE_VAT_CODES = ASSET_VAT_CODES

  private static final Set<VatCode> LIABILITY_VAT_CODES = [
      VatCode.OUTPUT_25,
      VatCode.OUTPUT_12,
      VatCode.OUTPUT_6,
      VatCode.REVERSE_CHARGE_EU_25,
      VatCode.REVERSE_CHARGE_DOMESTIC,
      VatCode.EXEMPT,
      VatCode.OUTSIDE_SCOPE
  ] as Set<VatCode>

  static List<VatCode> compatibleVatCodes(Account account) {
    VatCode.values().findAll { VatCode vatCode ->
      isVatCompatible(account, vatCode)
    }.toList()
  }

  static boolean isVatCompatibleClass(String accountClass) {
    accountClass in VAT_COMPATIBLE_CLASSES
  }

  private static boolean isVatCompatible(Account account, VatCode vatCode) {
    if (!isVatCompatibleClass(account.accountClass)) {
      return false
    }
    if (account.accountClass == ACCOUNT_CLASS_INCOME) {
      return vatCode in INCOME_VAT_CODES
    }
    if (account.accountClass == ACCOUNT_CLASS_EXPENSE) {
      return vatCode in EXPENSE_VAT_CODES
    }
    if (account.accountClass == ACCOUNT_CLASS_ASSET) {
      return vatCode in ASSET_VAT_CODES
    }
    account.accountClass == ACCOUNT_CLASS_LIABILITY && vatCode in LIABILITY_VAT_CODES
  }

  OpeningBalance getOpeningBalance(long fiscalYearId, String accountNumber) {
    String normalized = normalizeAccountNumber(accountNumber)
    databaseService.withSql { Sql sql ->
      long companyId = resolveCompanyId(sql, fiscalYearId)
      GroovyRowResult accountRow = sql.firstRow(
          'select id from account where company_id = ? and account_number = ?',
          [companyId, normalized]
      ) as GroovyRowResult
      if (accountRow == null) {
        throw new IllegalArgumentException("Konto ${normalized} finns inte i kontoplanen.")
      }
      long accountId = ((Number) accountRow.get('id')).longValue()
      GroovyRowResult row = sql.firstRow('''
          select fiscal_year_id as fiscalYearId,
                 account_id as accountId,
                 amount,
                 origin_type as originType,
                 source_fiscal_year_id as sourceFiscalYearId
            from opening_balance
           where fiscal_year_id = ?
             and account_id = ?
      ''', [fiscalYearId, accountId]) as GroovyRowResult
      row == null
          ? new OpeningBalance(fiscalYearId, accountId, normalized, BigDecimal.ZERO, OpeningBalanceService.ORIGIN_MANUAL, null)
          : new OpeningBalance(
              Long.valueOf(row.get('fiscalYearId').toString()),
              row.get('accountId') as Long,
              normalized,
              new BigDecimal(row.get('amount').toString()),
              row.get('originType') as String,
              row.get('sourceFiscalYearId') as Long
          )
    }
  }

  OpeningBalance saveOpeningBalance(long fiscalYearId, String accountNumber, BigDecimal amount) {
    String normalizedAccountNumber = normalizeAccountNumber(accountNumber)
    BigDecimal normalizedAmount = normalizeAmount(amount)
    databaseService.withTransaction { Sql sql ->
      long companyId = resolveCompanyId(sql, fiscalYearId)
      Account account = requireBalanceAccount(sql, companyId, normalizedAccountNumber)
      requireFiscalYear(sql, fiscalYearId)
      boolean exists = openingBalanceExists(sql, fiscalYearId, account.id)
      if (exists) {
        sql.executeUpdate('''
            update opening_balance
               set amount = ?,
                   origin_type = ?,
                   source_fiscal_year_id = null,
                   updated_at = current_timestamp
             where fiscal_year_id = ?
               and account_id = ?
        ''', [normalizedAmount, OpeningBalanceService.ORIGIN_MANUAL, fiscalYearId, account.id])
      } else {
        sql.executeInsert('''
            insert into opening_balance (
                fiscal_year_id,
                account_id,
                amount,
                origin_type,
                source_fiscal_year_id,
                created_at,
                updated_at
            ) values (?, ?, ?, ?, null, current_timestamp, current_timestamp)
        ''', [fiscalYearId, account.id, normalizedAmount, OpeningBalanceService.ORIGIN_MANUAL])
      }
      new OpeningBalance(
          fiscalYearId,
          account.id,
          account.accountNumber,
          normalizedAmount,
          OpeningBalanceService.ORIGIN_MANUAL,
          null
      )
    }
  }

  BigDecimal calculateAccountBalance(long companyId, long fiscalYearId, String accountNumber) {
    CompanyService.requireValidCompanyId(companyId)
    String normalized = normalizeAccountNumber(accountNumber)
    databaseService.withSql { Sql sql ->
      GroovyRowResult accountRow = sql.firstRow('''
          select id, normal_balance_side as normalBalanceSide
            from account
           where company_id = ?
             and account_number = ?
      ''', [companyId, normalized]) as GroovyRowResult
      if (accountRow == null) {
        return BigDecimal.ZERO.setScale(2)
      }
      long accountId = ((Number) accountRow.get('id')).longValue()
      String normalSide = accountRow.get('normalBalanceSide') as String

      GroovyRowResult openingRow = sql.firstRow('''
          select coalesce(amount, 0) as amount
            from opening_balance
           where fiscal_year_id = ?
             and account_id = ?
      ''', [fiscalYearId, accountId]) as GroovyRowResult
      BigDecimal opening = openingRow == null
          ? BigDecimal.ZERO
          : new BigDecimal(openingRow.get('amount').toString())

      GroovyRowResult transactionRow = sql.firstRow('''
          select coalesce(sum(vl.debit_amount), 0) as totalDebit,
                 coalesce(sum(vl.credit_amount), 0) as totalCredit
            from voucher_line vl
            join voucher v on v.id = vl.voucher_id
           where vl.account_id = ?
             and v.fiscal_year_id = ?
             and v.status in ('ACTIVE', 'CORRECTION')
      ''', [accountId, fiscalYearId]) as GroovyRowResult
      BigDecimal totalDebit = new BigDecimal(transactionRow.get('totalDebit').toString())
      BigDecimal totalCredit = new BigDecimal(transactionRow.get('totalCredit').toString())

      BigDecimal net = normalSide == 'CREDIT'
          ? totalCredit.subtract(totalDebit)
          : totalDebit.subtract(totalCredit)
      opening.add(net).setScale(2)
    }
  }

  BigDecimal calculateAccountBalanceBeforeVoucher(
      long companyId,
      long fiscalYearId,
      String accountNumber,
      Voucher voucher
  ) {
    CompanyService.requireValidCompanyId(companyId)
    String normalized = normalizeAccountNumber(accountNumber)
    databaseService.withSql { Sql sql ->
      GroovyRowResult accountRow = sql.firstRow('''
          select id, normal_balance_side as normalBalanceSide
            from account
           where company_id = ?
             and account_number = ?
      ''', [companyId, normalized]) as GroovyRowResult
      if (accountRow == null) {
        return BigDecimal.ZERO.setScale(2)
      }
      long accountId = ((Number) accountRow.get('id')).longValue()
      String normalSide = accountRow.get('normalBalanceSide') as String
      BigDecimal opening = openingBalance(sql, fiscalYearId, accountId)
      GroovyRowResult transactionRow = sql.firstRow('''
          select coalesce(sum(vl.debit_amount), 0) as totalDebit,
                 coalesce(sum(vl.credit_amount), 0) as totalCredit
            from voucher_line vl
            join voucher v on v.id = vl.voucher_id
           where vl.account_id = ?
             and v.fiscal_year_id = ?
             and v.status in ('ACTIVE', 'CORRECTION')
             and (v.accounting_date < ?
                  or (v.accounting_date = ? and
                      (coalesce(v.running_number, 2147483647) < ?
                       or (coalesce(v.running_number, 2147483647) = ? and v.id < ?))))
      ''', [accountId, fiscalYearId, voucher.accountingDate, voucher.accountingDate,
             voucher.runningNumber ?: Integer.MAX_VALUE, voucher.runningNumber ?: Integer.MAX_VALUE, voucher.id]) as GroovyRowResult
      BigDecimal totalDebit = new BigDecimal(transactionRow.get('totalDebit').toString())
      BigDecimal totalCredit = new BigDecimal(transactionRow.get('totalCredit').toString())
      BigDecimal net = normalSide == 'CREDIT'
          ? totalCredit.subtract(totalDebit)
          : totalDebit.subtract(totalCredit)
      opening.add(net).setScale(2)
    }
  }

  Map<String, BigDecimal> calculateAccountBalances(
      long companyId,
      long fiscalYearId,
      Collection<String> accountNumbers
  ) {
    CompanyService.requireValidCompanyId(companyId)
    Set<String> normalizedAccounts = new LinkedHashSet<>()
    accountNumbers.each { String accountNumber ->
      if (accountNumber?.trim()) {
        normalizedAccounts << normalizeAccountNumber(accountNumber)
      }
    }
    if (normalizedAccounts.isEmpty()) {
      return [:]
    }

    String accountPlaceholders = normalizedAccounts.collect { '?' }.join(', ')
    Map<String, BigDecimal> balances = [:]
    databaseService.withSql { Sql sql ->
      List<Object> accountParams = [fiscalYearId, companyId]
      accountParams.addAll(normalizedAccounts)
      List<GroovyRowResult> accounts = sql.rows("""
          select a.id,
                 a.account_number as accountNumber,
                 a.normal_balance_side as normalBalanceSide,
                 coalesce(ob.amount, 0) as openingAmount
            from account a
            left join opening_balance ob
              on ob.account_id = a.id
             and ob.fiscal_year_id = ?
           where a.company_id = ?
             and a.account_number in (${accountPlaceholders})
      """, accountParams) as List<GroovyRowResult>
      if (accounts.isEmpty()) {
        return
      }

      String transactionPlaceholders = accounts.collect { '?' }.join(', ')
      List<Object> transactionParams = accounts.collect { GroovyRowResult row ->
        ((Number) row.get('id')).longValue()
      } as List<Object>
      transactionParams << fiscalYearId
      List<GroovyRowResult> transactions = sql.rows("""
          select vl.account_id as accountId,
                 coalesce(sum(vl.debit_amount), 0) as totalDebit,
                 coalesce(sum(vl.credit_amount), 0) as totalCredit
            from voucher_line vl
            join voucher v on v.id = vl.voucher_id
           where vl.account_id in (${transactionPlaceholders})
             and v.fiscal_year_id = ?
             and v.status in ('ACTIVE', 'CORRECTION')
           group by vl.account_id
      """, transactionParams) as List<GroovyRowResult>
      Map<Long, GroovyRowResult> transactionsByAccountId = [:]
      transactions.each { GroovyRowResult row ->
        transactionsByAccountId[((Number) row.get('accountId')).longValue()] = row
      }

      accounts.each { GroovyRowResult account ->
        long accountId = ((Number) account.get('id')).longValue()
        GroovyRowResult transaction = transactionsByAccountId[accountId]
        BigDecimal totalDebit = transaction == null
            ? BigDecimal.ZERO
            : new BigDecimal(transaction.get('totalDebit').toString())
        BigDecimal totalCredit = transaction == null
            ? BigDecimal.ZERO
            : new BigDecimal(transaction.get('totalCredit').toString())
        BigDecimal net = account.get('normalBalanceSide') == 'CREDIT'
            ? totalCredit.subtract(totalDebit)
            : totalDebit.subtract(totalCredit)
        balances[account.get('accountNumber') as String] = new BigDecimal(
            account.get('openingAmount').toString()).add(net).setScale(2)
      }
    }
    balances
  }

  Map<String, BigDecimal> calculateAccountBalancesBeforeVoucher(
      long companyId,
      long fiscalYearId,
      Collection<String> accountNumbers,
      Voucher voucher
  ) {
    CompanyService.requireValidCompanyId(companyId)
    Set<String> normalizedAccounts = new LinkedHashSet<>()
    accountNumbers.each { String accountNumber ->
      if (accountNumber?.trim()) {
        normalizedAccounts << normalizeAccountNumber(accountNumber)
      }
    }
    if (normalizedAccounts.isEmpty()) {
      return [:]
    }

    String accountPlaceholders = normalizedAccounts.collect { '?' }.join(', ')
    Map<String, BigDecimal> balances = [:]
    databaseService.withSql { Sql sql ->
      List<Object> accountParams = [fiscalYearId, companyId]
      accountParams.addAll(normalizedAccounts)
      List<GroovyRowResult> accounts = sql.rows("""
          select a.id,
                 a.account_number as accountNumber,
                 a.normal_balance_side as normalBalanceSide,
                 coalesce(ob.amount, 0) as openingAmount
            from account a
            left join opening_balance ob
              on ob.account_id = a.id
             and ob.fiscal_year_id = ?
           where a.company_id = ?
             and a.account_number in (${accountPlaceholders})
      """, accountParams) as List<GroovyRowResult>
      if (accounts.isEmpty()) {
        return
      }

      String transactionPlaceholders = accounts.collect { '?' }.join(', ')
      List<Object> transactionParams = accounts.collect { GroovyRowResult row ->
        ((Number) row.get('id')).longValue()
      } as List<Object>
      transactionParams.addAll([fiscalYearId, voucher.accountingDate, voucher.accountingDate,
                                voucher.runningNumber ?: Integer.MAX_VALUE, voucher.runningNumber ?: Integer.MAX_VALUE, voucher.id])
      List<GroovyRowResult> transactions = sql.rows("""
          select vl.account_id as accountId,
                 coalesce(sum(vl.debit_amount), 0) as totalDebit,
                 coalesce(sum(vl.credit_amount), 0) as totalCredit
            from voucher_line vl
            join voucher v on v.id = vl.voucher_id
           where vl.account_id in (${transactionPlaceholders})
             and v.fiscal_year_id = ?
             and v.status in ('ACTIVE', 'CORRECTION')
             and (v.accounting_date < ?
                  or (v.accounting_date = ? and
                      (coalesce(v.running_number, 2147483647) < ?
                       or (coalesce(v.running_number, 2147483647) = ? and v.id < ?))))
           group by vl.account_id
      """, transactionParams) as List<GroovyRowResult>
      Map<Long, GroovyRowResult> transactionsByAccountId = [:]
      transactions.each { GroovyRowResult row ->
        transactionsByAccountId[((Number) row.get('accountId')).longValue()] = row
      }

      accounts.each { GroovyRowResult account ->
        long accountId = ((Number) account.get('id')).longValue()
        GroovyRowResult transaction = transactionsByAccountId[accountId]
        BigDecimal totalDebit = transaction == null
            ? BigDecimal.ZERO
            : new BigDecimal(transaction.get('totalDebit').toString())
        BigDecimal totalCredit = transaction == null
            ? BigDecimal.ZERO
            : new BigDecimal(transaction.get('totalCredit').toString())
        BigDecimal net = account.get('normalBalanceSide') == 'CREDIT'
            ? totalCredit.subtract(totalDebit)
            : totalDebit.subtract(totalCredit)
        balances[account.get('accountNumber') as String] = new BigDecimal(
            account.get('openingAmount').toString()).add(net).setScale(2)
      }
    }
    balances
  }

  private static BigDecimal openingBalance(Sql sql, long fiscalYearId, long accountId) {
    GroovyRowResult openingRow = sql.firstRow('''
        select coalesce(amount, 0) as amount
          from opening_balance
         where fiscal_year_id = ?
           and account_id = ?
    ''', [fiscalYearId, accountId]) as GroovyRowResult
    openingRow == null ? BigDecimal.ZERO : new BigDecimal(openingRow.get('amount').toString())
  }

  Map<String, String> normalBalanceSides(long companyId, Collection<String> accountNumbers) {
    CompanyService.requireValidCompanyId(companyId)
    Set<String> normalizedAccounts = new LinkedHashSet<>()
    accountNumbers.each { String accountNumber ->
      if (accountNumber?.trim()) {
        normalizedAccounts << normalizeAccountNumber(accountNumber)
      }
    }
    if (normalizedAccounts.isEmpty()) {
      return [:]
    }

    String placeholders = normalizedAccounts.collect { '?' }.join(', ')
    Map<String, String> sides = [:]
    databaseService.withSql { Sql sql ->
      List<Object> params = [companyId]
      params.addAll(normalizedAccounts)
      sql.rows("""
          select account_number as accountNumber,
                 normal_balance_side as normalBalanceSide
            from account
           where company_id = ?
             and account_number in (${placeholders})
      """, params).each { GroovyRowResult row ->
        sides[row.get('accountNumber') as String] = row.get('normalBalanceSide') as String
      }
    }
    sides
  }

  static BigDecimal calculateBalanceAfter(
      BigDecimal balanceBefore,
      BigDecimal debitAmount,
      BigDecimal creditAmount,
      String normalBalanceSide
  ) {
    BigDecimal safeBefore = balanceBefore ?: BigDecimal.ZERO
    BigDecimal safeDebit = debitAmount ?: BigDecimal.ZERO
    BigDecimal safeCredit = creditAmount ?: BigDecimal.ZERO
    BigDecimal net = normalBalanceSide == 'CREDIT'
        ? safeCredit.subtract(safeDebit)
        : safeDebit.subtract(safeCredit)
    safeBefore.add(net).setScale(2)
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

  private static boolean openingBalanceExists(Sql sql, long fiscalYearId, long accountId) {
    GroovyRowResult row = sql.firstRow(
        'select count(*) as total from opening_balance where fiscal_year_id = ? and account_id = ?',
        [fiscalYearId, accountId]
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

  private static Account requireAccount(Sql sql, long companyId, String accountNumber) {
    loadAccount(sql, companyId, accountNumber, "${UNKNOWN_ACCOUNT_MESSAGE_PREFIX} ${accountNumber}")
  }

  private static Account requireBalanceAccount(Sql sql, long companyId, String accountNumber) {
    Account account = loadAccount(sql, companyId, accountNumber, "${UNKNOWN_ACCOUNT_MESSAGE_PREFIX} ${accountNumber}")
    if (!account.isBalanceAccount()) {
      throw new IllegalArgumentException("Opening balances may only be stored on balance accounts: ${accountNumber}")
    }
    account
  }

  private static Account loadAccount(Sql sql, long companyId, String accountNumber, String missingMessage) {
    GroovyRowResult row = sql.firstRow('''
        select id,
               company_id as companyId,
               account_number as accountNumber,
               account_name as accountName,
               account_class as accountClass,
               normal_balance_side as normalBalanceSide,
               vat_code as vatCode,
               active,
               manual_review_required as manualReviewRequired,
               classification_note as classificationNote,
               account_subgroup as accountSubgroup
          from account
         where company_id = ?
           and account_number = ?
    ''', [companyId, accountNumber]) as GroovyRowResult
    if (row == null) {
      throw new IllegalArgumentException(missingMessage)
    }
    mapAccount(row)
  }

  private static Account mapAccount(GroovyRowResult row) {
    new Account(
        row.get('id') as Long,
        row.get('companyId') as Long,
        row.get('accountNumber') as String,
        row.get('accountName') as String,
        row.get('accountClass') as String,
        row.get('normalBalanceSide') as String,
        row.get('vatCode') as String,
        Boolean.TRUE == row.get('active'),
        Boolean.TRUE == row.get('manualReviewRequired'),
        row.get('classificationNote') as String,
        row.get('accountSubgroup') as String
    )
  }

  private static long resolveCompanyId(Sql sql, long fiscalYearId) {
    CompanyService.resolveFromFiscalYear(sql, fiscalYearId)
  }

  @Canonical
  static final class AccountOverview {

    int totalCount
    int activeCount
    int manualReviewCount
  }
}
