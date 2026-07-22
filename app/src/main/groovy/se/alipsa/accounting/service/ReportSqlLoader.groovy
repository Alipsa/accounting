package se.alipsa.accounting.service

import static se.alipsa.accounting.service.ReportAccountSupport.resolveSignedMovementNormalSide

import groovy.sql.GroovyRowResult
import groovy.sql.Sql

import java.math.RoundingMode
import java.sql.Date
import java.time.LocalDate

/**
 * Loads shared SQL row sets used by report builders.
 */
final class ReportSqlLoader {

  private ReportSqlLoader() {
  }

  static Map<String, AccountInfo> loadAccountInfos(Sql sql, long companyId) {
    Map<String, AccountInfo> accounts = [:]
    sql.rows('''
        select id as accountId,
               account_number as accountNumber,
               account_name as accountName,
               account_class as accountClass,
               normal_balance_side as normalBalanceSide,
               account_subgroup as accountSubgroup
          from account
         where company_id = ?
         order by account_number
    ''', [companyId]).each { GroovyRowResult row ->
      accounts.put(row.get('accountNumber') as String, new AccountInfo(
          ((Number) row.get('accountId')).longValue(),
          row.get('accountName') as String,
          row.get('accountClass') as String,
          row.get('normalBalanceSide') as String,
          row.get('accountSubgroup') as String
      ))
    }
    accounts
  }

  static Map<String, BigDecimal> loadOpeningBalances(Sql sql, long fiscalYearId) {
    Map<String, BigDecimal> balances = [:]
    sql.rows('''
        select a.account_number as accountNumber,
               ob.amount
          from opening_balance ob
          join account a on a.id = ob.account_id
         where ob.fiscal_year_id = ?
    ''', [fiscalYearId]).each { GroovyRowResult row ->
      balances.put(row.get('accountNumber') as String, scale(new BigDecimal(row.get('amount').toString())))
    }
    balances
  }

  static Map<String, BigDecimal> loadSignedMovements(Sql sql, long fiscalYearId, LocalDate startDate, LocalDate endDate) {
    if (endDate.isBefore(startDate)) {
      return [:]
    }
    Map<String, BigDecimal> movements = [:]
    sql.rows('''
        select a.account_number as accountNumber,
               a.account_class as accountClass,
               a.normal_balance_side as normalBalanceSide,
               a.account_subgroup as accountSubgroup,
               sum(vl.debit_amount) as debitAmount,
               sum(vl.credit_amount) as creditAmount
          from voucher v
          join voucher_line vl on vl.voucher_id = v.id
          join account a on a.id = vl.account_id
         where v.fiscal_year_id = ?
           and v.status in ('ACTIVE', 'CORRECTION')
           and v.accounting_date between ? and ?
         group by a.account_number, a.account_class, a.normal_balance_side, a.account_subgroup
    ''', [fiscalYearId, Date.valueOf(startDate), Date.valueOf(endDate)]).each { GroovyRowResult row ->
      movements.put(row.get('accountNumber') as String, signedAmount(
          new BigDecimal(row.get('debitAmount').toString()),
          new BigDecimal(row.get('creditAmount').toString()),
          resolveSignedMovementNormalSide(
              row.get('accountNumber') as String,
              row.get('normalBalanceSide') as String,
              row.get('accountClass') as String,
              row.get('accountSubgroup') as String
          )
      ))
    }
    movements
  }

  static Map<String, Totals> loadPeriodTotals(Sql sql, long fiscalYearId, LocalDate startDate, LocalDate endDate) {
    Map<String, Totals> totals = [:]
    sql.rows('''
        select a.account_number as accountNumber,
               sum(vl.debit_amount) as debitAmount,
               sum(vl.credit_amount) as creditAmount
          from voucher v
          join voucher_line vl on vl.voucher_id = v.id
          join account a on a.id = vl.account_id
         where v.fiscal_year_id = ?
           and v.status in ('ACTIVE', 'CORRECTION')
           and v.accounting_date between ? and ?
         group by vl.account_id, a.account_number
    ''', [fiscalYearId, Date.valueOf(startDate), Date.valueOf(endDate)]).each { GroovyRowResult row ->
      totals.put(row.get('accountNumber') as String, new Totals(
          scale(new BigDecimal(row.get('debitAmount').toString())),
          scale(new BigDecimal(row.get('creditAmount').toString()))
      ))
    }
    totals
  }

  static Map<String, BigDecimal> loadBalanceSheetMovements(Sql sql, long fiscalYearId, LocalDate startDate, LocalDate endDate) {
    if (endDate.isBefore(startDate)) {
      return [:]
    }
    Map<String, BigDecimal> movements = [:]
    sql.rows('''
        select a.account_number as accountNumber,
               sum(vl.debit_amount) as debitAmount,
               sum(vl.credit_amount) as creditAmount
          from voucher v
          join voucher_line vl on vl.voucher_id = v.id
          join account a on a.id = vl.account_id
         where v.fiscal_year_id = ?
           and v.status in ('ACTIVE', 'CORRECTION')
           and v.accounting_date between ? and ?
           and a.account_class in ('ASSET', 'LIABILITY', 'EQUITY')
         group by a.account_number
    ''', [fiscalYearId, Date.valueOf(startDate), Date.valueOf(endDate)]).each { GroovyRowResult row ->
      movements.put(
          row.get('accountNumber') as String,
          scale(
              new BigDecimal(row.get('debitAmount').toString()) -
              new BigDecimal(row.get('creditAmount').toString())
          )
      )
    }
    movements
  }

  static List<PostingLine> loadPostingLines(Sql sql, long fiscalYearId, LocalDate startDate, LocalDate endDate) {
    sql.rows('''
        select v.id as voucherId,
               v.accounting_date as accountingDate,
               v.voucher_number as voucherNumber,
               v.description as voucherDescription,
               v.status,
               vl.line_index as lineIndex,
               vl.account_number as accountNumber,
               a.account_name as accountName,
               a.account_class as accountClass,
               a.normal_balance_side as normalBalanceSide,
               a.account_subgroup as accountSubgroup,
               vl.line_description as lineDescription,
               vl.debit_amount as debitAmount,
               vl.credit_amount as creditAmount
          from voucher v
          join voucher_line vl on vl.voucher_id = v.id
          join account a on a.id = vl.account_id
         where v.fiscal_year_id = ?
           and v.status in ('ACTIVE', 'CORRECTION')
           and v.accounting_date between ? and ?
    ''', [fiscalYearId, Date.valueOf(startDate), Date.valueOf(endDate)]).collect { GroovyRowResult row ->
      new PostingLine(
          Long.valueOf(row.get('voucherId').toString()),
          SqlValueMapper.toLocalDate(row.get('accountingDate')),
          row.get('voucherNumber') as String,
          row.get('voucherDescription') as String,
          row.get('status') as String,
          ((Number) row.get('lineIndex')).intValue(),
          row.get('accountNumber') as String,
          row.get('accountName') as String,
          row.get('accountClass') as String,
          resolveSignedMovementNormalSide(
              row.get('accountNumber') as String,
              row.get('normalBalanceSide') as String,
              row.get('accountClass') as String,
              row.get('accountSubgroup') as String
          ),
          row.get('lineDescription') as String,
          scale(new BigDecimal(row.get('debitAmount').toString())),
          scale(new BigDecimal(row.get('creditAmount').toString()))
      )
    }
  }

  /**
   * Converts amounts stored in normal-balance-side convention (positive on the account's own
   * normal side) to natural/ledger sign convention (negative for credit-normal accounts), matching
   * the sign convention of {@link #loadBalanceSheetMovements}.
   */
  static Map<String, BigDecimal> naturalSignBalances(Map<String, BigDecimal> normalSideBalances, Map<String, AccountInfo> accountInfos) {
    normalSideBalances.collectEntries { String accountNumber, BigDecimal amount ->
      String normalBalanceSide = accountInfos[accountNumber]?.normalBalanceSide
      [(accountNumber): normalBalanceSide == 'CREDIT' ? scale(amount?.negate()) : scale(amount)]
    } as Map<String, BigDecimal>
  }

  private static BigDecimal signedAmount(BigDecimal debitAmount, BigDecimal creditAmount, String normalBalanceSide) {
    String safeNormalBalanceSide = normalBalanceSide?.trim()?.toUpperCase(Locale.ROOT)
    if (!safeNormalBalanceSide) {
      throw new IllegalStateException('Kontot saknar normal balanssida för rapportering.')
    }
    safeNormalBalanceSide == 'DEBIT'
        ? scale(debitAmount - creditAmount)
        : scale(creditAmount - debitAmount)
  }

  private static BigDecimal scale(BigDecimal amount) {
    (amount ?: BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)
  }
}
