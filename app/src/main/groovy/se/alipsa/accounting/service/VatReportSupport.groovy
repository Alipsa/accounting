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
  private static final Set<VatCode> REVERSE_CHARGE_BASE_CODES = [
      VatCode.REVERSE_CHARGE_DOMESTIC,
      VatCode.EU_ACQUISITION_GOODS,
      VatCode.EU_ACQUISITION_SERVICES
  ] as Set<VatCode>
  private static final Set<VatCode> SHARED_REVERSE_CHARGE_OUTPUT_CODES = [
      VatCode.REVERSE_CHARGE_EU_25
  ] as Set<VatCode>

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
        select v.id as voucherId,
               a.vat_code as vatCode,
               a.account_class as accountClass,
               a.normal_balance_side as normalBalanceSide,
               sum(vl.debit_amount) as debitAmount,
               sum(vl.credit_amount) as creditAmount
          from voucher v
          join voucher_line vl on vl.voucher_id = v.id
          join account a on a.id = vl.account_id
         where v.fiscal_year_id = ?
           and v.status in ('ACTIVE', 'CORRECTION')
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
         group by v.id, a.vat_code, a.account_class, a.normal_balance_side
         order by v.id, a.vat_code, a.account_class
    ''')

    List<RawVatLine> rawLines = sql.rows(query.toString(), params).collect { GroovyRowResult row ->
      VatCode vatCode = parseVatCode(row.get('vatCode') as String)
      BigDecimal signedAmount = signedAmount(
          new BigDecimal(row.get('debitAmount').toString()),
          new BigDecimal(row.get('creditAmount').toString()),
          row.get('normalBalanceSide') as String
      )
      new RawVatLine(
          ((Number) row.get('voucherId')).longValue(),
          vatCode,
          row.get('accountClass') as String,
          signedAmount
      )
    }

    rawLines
        .groupBy { RawVatLine line -> line.voucherId }
        .values()
        .collectMany { List<RawVatLine> voucherLines ->
          classifyVoucher(voucherLines)
        }
  }

  private static List<VatSeed> classifyVoucher(List<RawVatLine> lines) {
    List<VatSeed> seeds = []
    Map<VatCode, BigDecimal> reverseBaseAmounts = [:].withDefault { BigDecimal.ZERO }
    List<RawVatLine> deferredOutputLines = []

    lines.each { RawVatLine line ->
      if (isSharedReverseChargeOutputLine(line)) {
        deferredOutputLines << line
        return
      }
      VatSeed seed = classify(line.vatCode, line.accountClass, line.signedAmount)
      seeds << seed
      if (line.accountClass in BASE_ACCOUNT_CLASSES && line.vatCode in REVERSE_CHARGE_BASE_CODES) {
        reverseBaseAmounts[line.vatCode] = scale(reverseBaseAmounts[line.vatCode] + seed.baseAmount)
      }
    }

    deferredOutputLines.each { RawVatLine line ->
      seeds.addAll(classifySharedReverseChargeOutput(line, reverseBaseAmounts))
    }
    seeds
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

  private static boolean isSharedReverseChargeOutputLine(RawVatLine line) {
    line.accountClass == 'LIABILITY' && line.vatCode in SHARED_REVERSE_CHARGE_OUTPUT_CODES
  }

  private static List<VatSeed> classifySharedReverseChargeOutput(
      RawVatLine line, Map<VatCode, BigDecimal> reverseBaseAmounts
  ) {
    List<Map.Entry<VatCode, BigDecimal>> matchingBases = reverseBaseAmounts.entrySet().findAll { Map.Entry<VatCode, BigDecimal> entry ->
      entry.value != BigDecimal.ZERO && entry.key.outputRate == line.vatCode.outputRate
    }.toList()
    if (matchingBases.isEmpty()) {
      return [classify(line.vatCode, line.accountClass, line.signedAmount)]
    }

    BigDecimal totalComputedOutput = matchingBases.sum(BigDecimal.ZERO) { Map.Entry<VatCode, BigDecimal> entry ->
      scale(entry.value * entry.key.outputRate).abs()
    } as BigDecimal
    if (totalComputedOutput == BigDecimal.ZERO) {
      return [classify(line.vatCode, line.accountClass, line.signedAmount)]
    }

    BigDecimal allocated = BigDecimal.ZERO
    matchingBases.withIndex().collect { Map.Entry<VatCode, BigDecimal> entry, int index ->
      BigDecimal outputVat = index == matchingBases.size() - 1
          ? scale(line.signedAmount - allocated)
          : scale(line.signedAmount * scale(entry.value * entry.key.outputRate).abs() / totalComputedOutput)
      allocated = scale(allocated + outputVat)
      new VatSeed(
          entry.key,
          BigDecimal.ZERO.setScale(AMOUNT_SCALE),
          outputVat,
          BigDecimal.ZERO.setScale(AMOUNT_SCALE),
          1,
          0
      )
    }
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
  private static final class RawVatLine {

    long voucherId
    VatCode vatCode
    String accountClass
    BigDecimal signedAmount
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
