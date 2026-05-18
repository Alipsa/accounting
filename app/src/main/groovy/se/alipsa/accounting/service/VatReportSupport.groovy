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
  private static final Set<String> VAT_BALANCE_ACCOUNT_CLASSES = [
      AccountService.ACCOUNT_CLASS_ASSET,
      AccountService.ACCOUNT_CLASS_LIABILITY
  ] as Set<String>
  private static final Set<String> BASE_ACCOUNT_CLASSES = [
      AccountService.ACCOUNT_CLASS_INCOME,
      AccountService.ACCOUNT_CLASS_EXPENSE
  ] as Set<String>
  private static final Set<VatCode> REVERSE_CHARGE_BASE_CODES = [
      VatCode.REVERSE_CHARGE_DOMESTIC,
      VatCode.EU_ACQUISITION_GOODS,
      VatCode.EU_ACQUISITION_SERVICES
  ] as Set<VatCode>
  private static final Set<VatCode> EU_REVERSE_CHARGE_BASE_CODES = [
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
    List<VatSeed> seeds = loadAggregatedSeeds(sql, fiscalYearId, startDate, endDate, excludeVatTransferVouchers)
    List<RawVatLine> sharedOutputLines = loadSharedReverseChargeOutputLines(
        sql, fiscalYearId, startDate, endDate, excludeVatTransferVouchers)
    if (sharedOutputLines.isEmpty()) {
      return seeds
    }

    Map<Long, Map<VatCode, BigDecimal>> reverseBaseAmountsByVoucher = loadReverseBaseAmountsByVoucher(
        sql, fiscalYearId, startDate, endDate, excludeVatTransferVouchers)
    sharedOutputLines.each { RawVatLine line ->
      seeds.addAll(classifySharedReverseChargeOutput(line, reverseBaseAmountsByVoucher[line.voucherId] ?: [:]))
    }
    seeds
  }

  private static List<VatSeed> loadAggregatedSeeds(
      Sql sql,
      long fiscalYearId,
      LocalDate startDate,
      LocalDate endDate,
      boolean excludeVatTransferVouchers
  ) {
    StringBuilder query = new StringBuilder('''
        select a.vat_code as vatCode,
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
    appendTransferExclusion(query, params, fiscalYearId, startDate, endDate, excludeVatTransferVouchers)
    appendExcludedVatCodes(query, params, SHARED_REVERSE_CHARGE_OUTPUT_CODES)
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

  private static List<RawVatLine> loadSharedReverseChargeOutputLines(
      Sql sql,
      long fiscalYearId,
      LocalDate startDate,
      LocalDate endDate,
      boolean excludeVatTransferVouchers
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
           and a.account_class = ?
    ''')
    List<Object> params = [
        fiscalYearId,
        Date.valueOf(startDate),
        Date.valueOf(endDate),
        AccountService.ACCOUNT_CLASS_LIABILITY
    ]
    appendTransferExclusion(query, params, fiscalYearId, startDate, endDate, excludeVatTransferVouchers)
    appendIncludedVatCodes(query, params, SHARED_REVERSE_CHARGE_OUTPUT_CODES)
    query.append('''
         group by v.id, a.vat_code, a.account_class, a.normal_balance_side
         order by v.id, a.vat_code, a.account_class
    ''')

    sql.rows(query.toString(), params).collect { GroovyRowResult row ->
      toRawVatLine(row)
    }
  }

  private static Map<Long, Map<VatCode, BigDecimal>> loadReverseBaseAmountsByVoucher(
      Sql sql,
      long fiscalYearId,
      LocalDate startDate,
      LocalDate endDate,
      boolean excludeVatTransferVouchers
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
           and a.account_class in (?, ?)
           and exists (
                 select 1
                   from voucher_line shared_vl
                   join account shared_a on shared_a.id = shared_vl.account_id
                  where shared_vl.voucher_id = v.id
                    and shared_a.account_class = ?
    ''')
    List<Object> params = [
        fiscalYearId,
        Date.valueOf(startDate),
        Date.valueOf(endDate),
        AccountService.ACCOUNT_CLASS_EXPENSE,
        AccountService.ACCOUNT_CLASS_ASSET,
        AccountService.ACCOUNT_CLASS_LIABILITY
    ]
    appendIncludedVatCodes(query, params, SHARED_REVERSE_CHARGE_OUTPUT_CODES, 'shared_a')
    query.append('''
           )
    ''')
    appendTransferExclusion(query, params, fiscalYearId, startDate, endDate, excludeVatTransferVouchers)
    appendIncludedVatCodes(query, params, REVERSE_CHARGE_BASE_CODES)
    query.append('''
         group by v.id, a.vat_code, a.account_class, a.normal_balance_side
         order by v.id, a.vat_code, a.account_class
    ''')

    Map<Long, Map<VatCode, BigDecimal>> result = [:]
    sql.rows(query.toString(), params).each { GroovyRowResult row ->
      RawVatLine line = toRawVatLine(row)
      Map<VatCode, BigDecimal> voucherAmounts = result.computeIfAbsent(line.voucherId) {
        [:].withDefault { BigDecimal.ZERO }
      }
      voucherAmounts[line.vatCode] = scale(voucherAmounts[line.vatCode] + line.signedAmount)
    }
    result
  }

  private static void appendTransferExclusion(
      StringBuilder query,
      List<Object> params,
      long fiscalYearId,
      LocalDate startDate,
      LocalDate endDate,
      boolean excludeVatTransferVouchers
  ) {
    if (!excludeVatTransferVouchers) {
      return
    }
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
    params << Date.valueOf(endDate)   // vp.start_date <= report end (overlap: period must start before report ends)
    params << Date.valueOf(startDate) // vp.end_date >= report start (overlap: period must end after report starts)
  }

  private static void appendIncludedVatCodes(
      StringBuilder query,
      List<Object> params,
      Set<VatCode> vatCodes,
      String accountAlias = 'a'
  ) {
    if (vatCodes.isEmpty()) {
      throw new IllegalArgumentException('appendIncludedVatCodes called with empty VAT code set.')
    }
    query.append(" and ${accountAlias}.vat_code in (${placeholders(vatCodes.size())})")
    params.addAll(vatCodes.collect { VatCode vatCode -> vatCode.name() })
  }

  private static void appendExcludedVatCodes(StringBuilder query, List<Object> params, Set<VatCode> vatCodes) {
    if (vatCodes.isEmpty()) {
      return
    }
    query.append(" and a.vat_code not in (${placeholders(vatCodes.size())})")
    params.addAll(vatCodes.collect { VatCode vatCode -> vatCode.name() })
  }

  private static String placeholders(int count) {
    (['?'] * count).join(', ')
  }

  private static RawVatLine toRawVatLine(GroovyRowResult row) {
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

  private static List<VatSeed> classifySharedReverseChargeOutput(
      RawVatLine line, Map<VatCode, BigDecimal> reverseBaseAmounts
  ) {
    Set<VatCode> expectedBaseCodes = sharedReverseChargeBaseCodes(line.vatCode)
    List<Map.Entry<VatCode, BigDecimal>> matchingBases = reverseBaseAmounts.entrySet().findAll { Map.Entry<VatCode, BigDecimal> entry ->
      (entry.value <=> BigDecimal.ZERO) != 0 && entry.key in expectedBaseCodes
    }.toList()
    if (matchingBases.isEmpty()) {
      return [classify(line.vatCode, line.accountClass, line.signedAmount)]
    }

    BigDecimal totalComputedOutput = matchingBases.sum(BigDecimal.ZERO) { Map.Entry<VatCode, BigDecimal> entry ->
      scale(entry.value * entry.key.outputRate).abs()
    } as BigDecimal
    // Safety net: EU acquisition codes have outputRate=0.25, so this only fires if base amounts
    // somehow net to zero after scaling — fall back to treating the whole output line as one bucket.
    if (totalComputedOutput == BigDecimal.ZERO) {
      return [classify(line.vatCode, line.accountClass, line.signedAmount)]
    }

    BigDecimal allocated = BigDecimal.ZERO
    matchingBases.withIndex().collect { Map.Entry<VatCode, BigDecimal> entry, int index ->
      // Let the final bucket absorb cent rounding so allocations add back to the posted VAT line.
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

  private static Set<VatCode> sharedReverseChargeBaseCodes(VatCode outputVatCode) {
    outputVatCode == VatCode.REVERSE_CHARGE_EU_25 ? EU_REVERSE_CHARGE_BASE_CODES : Collections.emptySet()
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
