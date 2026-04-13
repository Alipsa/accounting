package se.alipsa.accounting.service

import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.transform.Canonical

import se.alipsa.accounting.domain.VatCode

import java.math.RoundingMode
import java.sql.Date
import java.time.LocalDate

/**
 * Shared VAT aggregation rules used by both VAT periods and ad hoc report exports.
 */
final class VatReportSupport {

  private static final int AMOUNT_SCALE = 2
  private static final Set<String> VAT_BALANCE_ACCOUNT_CLASSES = ['ASSET', 'LIABILITY'] as Set<String>
  private static final Set<String> BASE_ACCOUNT_CLASSES = ['INCOME', 'EXPENSE'] as Set<String>

  private VatReportSupport() {
  }

  static List<VatSeed> loadSeeds(
      Sql sql,
      long fiscalYearId,
      LocalDate startDate,
      LocalDate endDate,
      boolean excludeVatTransferVouchers = false
  ) {
    StringBuilder query = new StringBuilder('''
        select a.vat_code as vatCode,
               a.account_class as accountClass,
               a.normal_balance_side as normalBalanceSide,
               sum(vl.debit_amount) as debitAmount,
               sum(vl.credit_amount) as creditAmount
          from voucher v
          join voucher_line vl on vl.voucher_id = v.id
          join account a on a.account_number = vl.account_number
         where v.fiscal_year_id = ?
           and v.status in ('BOOKED', 'CORRECTION')
           and v.accounting_date between ? and ?
           and a.vat_code is not null
    ''')
    List<Object> params = [fiscalYearId, Date.valueOf(startDate), Date.valueOf(endDate)]
    if (excludeVatTransferVouchers) {
      query.append('''
           and not exists (
                 select 1
                   from vat_period vp
                  where vp.fiscal_year_id = ?
                    and vp.transfer_voucher_id = v.id
                    and vp.start_date <= ?
                    and vp.end_date >= ?
           )
      ''')
      params << fiscalYearId
      params << Date.valueOf(endDate)
      params << Date.valueOf(startDate)
    }
    query.append('''
         group by a.vat_code, a.account_class, a.normal_balance_side
         order by a.vat_code, a.account_class
    ''')

    sql.rows(query.toString(), params).collect { GroovyRowResult row ->
      VatCode vatCode = parseVatCode(row.get('vatCode') as String)
      BigDecimal signedAmount = signedAmount(
          new BigDecimal(row.get('debitAmount').toString()),
          new BigDecimal(row.get('creditAmount').toString()),
          row.get('normalBalanceSide') as String
      )
      classify(vatCode, row.get('accountClass') as String, signedAmount)
    }
  }

  private static VatSeed classify(VatCode vatCode, String accountClass, BigDecimal signedAmount) {
    BigDecimal baseAmount = BigDecimal.ZERO
    BigDecimal postedOutputVat = BigDecimal.ZERO
    BigDecimal postedInputVat = BigDecimal.ZERO
    int outputPostingCount = 0
    int inputPostingCount = 0

    if (accountClass in BASE_ACCOUNT_CLASSES) {
      baseAmount = signedAmount
    } else if (accountClass in VAT_BALANCE_ACCOUNT_CLASSES) {
      if (isOutputVatAccount(vatCode, accountClass)) {
        postedOutputVat = signedAmount
        outputPostingCount = 1
      } else if (isInputVatAccount(vatCode, accountClass)) {
        postedInputVat = signedAmount
        inputPostingCount = 1
      }
    } else {
      throw new IllegalStateException("Momskod ${vatCode.name()} får inte användas på kontoklass ${accountClass}.")
    }

    new VatSeed(
        vatCode,
        scale(baseAmount),
        scale(postedOutputVat),
        scale(postedInputVat),
        outputPostingCount,
        inputPostingCount
    )
  }

  private static boolean isOutputVatAccount(VatCode vatCode, String accountClass) {
    vatCode.outputRate > BigDecimal.ZERO && accountClass == 'LIABILITY'
  }

  private static boolean isInputVatAccount(VatCode vatCode, String accountClass) {
    vatCode.inputRate > BigDecimal.ZERO && accountClass == 'ASSET'
  }

  private static VatCode parseVatCode(String value) {
    try {
      VatCode vatCode = VatCode.fromDatabaseValue(value)
      if (vatCode == null) {
        throw new IllegalStateException('Momskod saknas för momsrad.')
      }
      return vatCode
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("Okänd momskod i databasen: ${value}", exception)
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

  @Canonical
  static final class VatSeed {

    VatCode vatCode
    BigDecimal baseAmount
    BigDecimal postedOutputVat
    BigDecimal postedInputVat
    int outputPostingCount
    int inputPostingCount
  }
}
