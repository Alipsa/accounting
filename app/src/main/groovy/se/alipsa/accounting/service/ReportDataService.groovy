package se.alipsa.accounting.service

import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.transform.Canonical

import se.alipsa.accounting.domain.AccountSubgroup
import se.alipsa.accounting.domain.AccountingPeriod
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.VatCode
import se.alipsa.accounting.domain.report.BalanceSheetRow
import se.alipsa.accounting.domain.report.BalanceSheetSection
import se.alipsa.accounting.domain.report.GeneralLedgerRow
import se.alipsa.accounting.domain.report.IncomeStatementRow
import se.alipsa.accounting.domain.report.IncomeStatementRowType
import se.alipsa.accounting.domain.report.IncomeStatementSection
import se.alipsa.accounting.domain.report.ReportResult
import se.alipsa.accounting.domain.report.ReportSelection
import se.alipsa.accounting.domain.report.ReportType
import se.alipsa.accounting.domain.report.TransactionReportRow
import se.alipsa.accounting.domain.report.TrialBalanceRow
import se.alipsa.accounting.domain.report.VatReportEntry
import se.alipsa.accounting.domain.report.VoucherListRow
import se.alipsa.accounting.support.I18n

import java.math.RoundingMode
import java.sql.Date
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.time.LocalDate
import java.util.logging.Logger

/**
 * Builds reusable report data for UI previews, CSV/Excel export and Journo (FreeMarker) PDF rendering.
 */
final class ReportDataService {

  private static final Logger log = Logger.getLogger(ReportDataService.name)
  private static final int AMOUNT_SCALE = 2

  private final DatabaseService databaseService
  private final FiscalYearService fiscalYearService
  private final AccountingPeriodService accountingPeriodService

  ReportDataService() {
    this(DatabaseService.instance)
  }

  ReportDataService(DatabaseService databaseService) {
    this(databaseService, new FiscalYearService(databaseService), new AccountingPeriodService(databaseService))
  }

  ReportDataService(
      DatabaseService databaseService,
      FiscalYearService fiscalYearService,
      AccountingPeriodService accountingPeriodService
  ) {
    this.databaseService = databaseService
    this.fiscalYearService = fiscalYearService
    this.accountingPeriodService = accountingPeriodService
  }

  ReportResult generate(ReportSelection selection) {
    EffectiveSelection effective = resolveSelection(selection)
    switch (effective.selection.reportType) {
      case ReportType.VOUCHER_LIST:
        return buildVoucherListReport(effective)
      case ReportType.GENERAL_LEDGER:
        return buildGeneralLedgerReport(effective)
      case ReportType.TRIAL_BALANCE:
        return buildTrialBalanceReport(effective)
      case ReportType.INCOME_STATEMENT:
        return buildIncomeStatementReport(effective)
      case ReportType.BALANCE_SHEET:
        return buildBalanceSheetReport(effective)
      case ReportType.TRANSACTION_REPORT:
        return buildTransactionReport(effective)
      case ReportType.VAT_REPORT:
        return buildVatReport(effective)
      default:
        throw new IllegalArgumentException("Rapporttypen stöds inte: ${effective.selection.reportType}")
    }
  }

  private ReportResult buildVoucherListReport(EffectiveSelection effective) {
    List<VoucherListRow> rows = loadVoucherListRows(effective)
    List<List<String>> tableRows = rows.collect { VoucherListRow row ->
      stringRow(
          row.accountingDate.toString(),
          row.voucherNumber,
          row.seriesCode,
          row.description,
          row.status,
          formatAmount(row.debitAmount),
          formatAmount(row.creditAmount)
      )
    }
    BigDecimal debitTotal = rows.sum(BigDecimal.ZERO) { VoucherListRow row -> row.debitAmount } as BigDecimal
    BigDecimal creditTotal = rows.sum(BigDecimal.ZERO) { VoucherListRow row -> row.creditAmount } as BigDecimal
    createResult(
        effective,
        [
            I18n.instance.format('voucherListReport.summary.count', rows.size()),
            I18n.instance.format('voucherListReport.summary.debitTotal', formatAmount(scale(debitTotal))),
            I18n.instance.format('voucherListReport.summary.creditTotal', formatAmount(scale(creditTotal)))
        ],
        voucherListHeaders(),
        tableRows,
        rows.collect { VoucherListRow row -> row.voucherId },
        [typedRows: rows, lead: I18n.instance.getString('report.voucherList.lead')]
    )
  }

  private List<VoucherListRow> loadVoucherListRows(EffectiveSelection effective) {
    databaseService.withSql { Sql sql ->
      sql.rows('''
          select v.id as voucherId,
                 v.accounting_date as accountingDate,
                 v.voucher_number as voucherNumber,
                 s.series_code as seriesCode,
                 v.description,
                 v.status,
                 sum(vl.debit_amount) as debitAmount,
                 sum(vl.credit_amount) as creditAmount
            from voucher v
            join voucher_series s on s.id = v.voucher_series_id
            join voucher_line vl on vl.voucher_id = v.id
           where v.fiscal_year_id = ?
             and v.status in ('ACTIVE', 'CORRECTION')
             and v.accounting_date between ? and ?
           group by v.id, v.accounting_date, v.voucher_number, s.series_code, v.description, v.status
           order by v.accounting_date, coalesce(v.running_number, 2147483647), v.id
      ''', [effective.selection.fiscalYearId, Date.valueOf(effective.startDate), Date.valueOf(effective.endDate)]).collect { GroovyRowResult row ->
        new VoucherListRow(
            Long.valueOf(row.get('voucherId').toString()),
            SqlValueMapper.toLocalDate(row.get('accountingDate')),
            row.get('voucherNumber') as String,
            row.get('seriesCode') as String,
            row.get('description') as String,
            row.get('status') as String,
            scale(new BigDecimal(row.get('debitAmount').toString())),
            scale(new BigDecimal(row.get('creditAmount').toString()))
        )
      }
    }
  }

  private static List<String> voucherListHeaders() {
    [
        I18n.instance.getString('voucherListReport.column.date'),
        I18n.instance.getString('voucherListReport.column.voucher'),
        I18n.instance.getString('voucherListReport.column.series'),
        I18n.instance.getString('voucherListReport.column.text'),
        I18n.instance.getString('voucherListReport.column.status'),
        I18n.instance.getString('voucherListReport.column.debit'),
        I18n.instance.getString('voucherListReport.column.credit')
    ]
  }

  @SuppressWarnings('AbcMetric')
  private ReportResult buildGeneralLedgerReport(EffectiveSelection effective) {
    databaseService.withSql { Sql sql ->
      Map<String, AccountInfo> accountInfos = loadAccountInfos(sql, effective.companyId)
      Map<String, BigDecimal> openingBalances = loadOpeningBalances(sql, effective.selection.fiscalYearId)
      Map<String, BigDecimal> priorBalances = effective.startDate.isAfter(effective.fiscalYear.startDate)
          ? loadSignedMovements(sql, effective.selection.fiscalYearId, effective.fiscalYear.startDate, effective.startDate.minusDays(1))
          : [:]
      List<PostingLine> postingLines = loadPostingLines(sql, effective.selection.fiscalYearId, effective.startDate, effective.endDate)
          .sort { PostingLine line ->
            [line.accountNumber, line.accountingDate, line.voucherNumber ?: '', line.voucherId, line.lineIndex]
          }
      Map<String, List<PostingLine>> postingsByAccount = postingLines.groupBy { PostingLine line -> line.accountNumber }
      List<GeneralLedgerRow> rows = []
      accountInfos.keySet().sort().each { String accountNumber ->
        BigDecimal opening = scale((openingBalances[accountNumber] ?: BigDecimal.ZERO) + (priorBalances[accountNumber] ?: BigDecimal.ZERO))
        List<PostingLine> accountLines = postingsByAccount[accountNumber] ?: []
        if (opening == BigDecimal.ZERO && accountLines.isEmpty()) {
          return
        }
        AccountInfo info = accountInfos[accountNumber]
        rows << new GeneralLedgerRow(
            accountNumber,
            info.accountName,
            null,
            null,
            I18n.instance.getString('generalLedgerReport.row.openingBalance'),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            opening,
            null
        )
        BigDecimal runningBalance = opening
        accountLines.each { PostingLine line ->
          runningBalance = scale(runningBalance + line.signedAmount())
          rows << new GeneralLedgerRow(
              line.accountNumber,
              line.accountName,
              line.accountingDate,
              line.voucherNumber,
              line.lineDescription ?: line.voucherDescription,
              line.debitAmount,
              line.creditAmount,
              runningBalance,
              line.voucherId
          )
        }
      }
      List<String> headers = [
          I18n.instance.getString('generalLedgerReport.column.account'),
          I18n.instance.getString('generalLedgerReport.column.name'),
          I18n.instance.getString('generalLedgerReport.column.date'),
          I18n.instance.getString('generalLedgerReport.column.voucher'),
          I18n.instance.getString('generalLedgerReport.column.text'),
          I18n.instance.getString('generalLedgerReport.column.debit'),
          I18n.instance.getString('generalLedgerReport.column.credit'),
          I18n.instance.getString('generalLedgerReport.column.balance')
      ]
      List<List<String>> tableRows = rows.collect { GeneralLedgerRow row ->
        stringRow(
            row.accountNumber,
            row.accountName,
            row.accountingDate?.toString() ?: '',
            row.voucherNumber ?: '',
            row.description ?: '',
            formatAmount(row.debitAmount),
            formatAmount(row.creditAmount),
            formatAmount(row.balance)
        )
      }
      createResult(
          effective,
          [I18n.instance.format('generalLedgerReport.summary.rowCount', rows.size())],
          headers,
          tableRows,
          rows.collect { GeneralLedgerRow row -> row.voucherId },
          [
              typedRows: rows,
              lead: I18n.instance.getString('report.generalLedger.lead'),
              note: I18n.instance.getString('report.generalLedger.note')
          ]
      )
    } as ReportResult
  }

  @SuppressWarnings('AbcMetric')
  private ReportResult buildTrialBalanceReport(EffectiveSelection effective) {
    databaseService.withSql { Sql sql ->
      Map<String, AccountInfo> accountInfos = loadAccountInfos(sql, effective.companyId)
      Map<String, BigDecimal> openingBalances = loadOpeningBalances(sql, effective.selection.fiscalYearId)
      Map<String, BigDecimal> priorBalances = effective.startDate.isAfter(effective.fiscalYear.startDate)
          ? loadSignedMovements(sql, effective.selection.fiscalYearId, effective.fiscalYear.startDate, effective.startDate.minusDays(1))
          : [:]
      Map<String, Totals> periodTotals = loadPeriodTotals(sql, effective.selection.fiscalYearId, effective.startDate, effective.endDate)
      List<TrialBalanceRow> rows = accountInfos.keySet().sort().collect { String accountNumber ->
        AccountInfo info = accountInfos[accountNumber]
        Totals totals = periodTotals[accountNumber] ?: Totals.ZERO
        String balanceSide = resolveTrialBalanceNormalSide(accountNumber, info)
        BigDecimal opening = scale((openingBalances[accountNumber] ?: BigDecimal.ZERO) + (priorBalances[accountNumber] ?: BigDecimal.ZERO))
        BigDecimal closing = scale(opening + signedAmount(totals.debitAmount, totals.creditAmount, balanceSide))
        if (opening == BigDecimal.ZERO && totals.debitAmount == BigDecimal.ZERO && totals.creditAmount == BigDecimal.ZERO && closing == BigDecimal.ZERO) {
          return null
        }
        new TrialBalanceRow(accountNumber, info.accountName, opening, totals.debitAmount, totals.creditAmount, closing)
      }.findAll { TrialBalanceRow row -> row != null }
      BigDecimal openingTotal = rows.sum(BigDecimal.ZERO) { TrialBalanceRow row -> row.openingBalance } as BigDecimal
      BigDecimal debitTotal = rows.sum(BigDecimal.ZERO) { TrialBalanceRow row -> row.debitAmount } as BigDecimal
      BigDecimal creditTotal = rows.sum(BigDecimal.ZERO) { TrialBalanceRow row -> row.creditAmount } as BigDecimal
      BigDecimal closingTotal = rows.sum(BigDecimal.ZERO) { TrialBalanceRow row -> row.closingBalance } as BigDecimal
      createResult(
          effective,
          [
              I18n.instance.format('trialBalanceReport.summary.openingBalance', formatAmount(scale(openingTotal))),
              I18n.instance.format('trialBalanceReport.summary.periodDebit', formatAmount(scale(debitTotal))),
              I18n.instance.format('trialBalanceReport.summary.periodCredit', formatAmount(scale(creditTotal))),
              I18n.instance.format('trialBalanceReport.summary.closingBalance', formatAmount(scale(closingTotal)))
          ],
          [
              I18n.instance.getString('trialBalanceReport.column.account'),
              I18n.instance.getString('trialBalanceReport.column.name'),
              I18n.instance.getString('trialBalanceReport.column.opening'),
              I18n.instance.getString('trialBalanceReport.column.debit'),
              I18n.instance.getString('trialBalanceReport.column.credit'),
              I18n.instance.getString('trialBalanceReport.column.closing')
          ],
          rows.collect { TrialBalanceRow row ->
            stringRow(
                row.accountNumber,
                row.accountName,
                formatAmount(row.openingBalance),
                formatAmount(row.debitAmount),
                formatAmount(row.creditAmount),
                formatAmount(row.closingBalance)
            )
          },
          rows.collect { TrialBalanceRow ignored -> null as Long } as List<Long>,
          [typedRows: rows]
      )
    } as ReportResult
  }

  @SuppressWarnings('AbcMetric')
  private ReportResult buildIncomeStatementReport(EffectiveSelection effective) {
    databaseService.withSql { Sql sql ->
      Map<String, AccountInfo> accountInfos = loadAccountInfos(sql, effective.companyId)
      Map<String, Totals> periodTotals = loadPeriodTotals(sql, effective.selection.fiscalYearId, effective.startDate, effective.endDate)

      Map<AccountSubgroup, List<AccountDetail>> subgroupAccounts = buildIncomeAccounts(accountInfos, periodTotals)
      Map<AccountSubgroup, BigDecimal> subgroupTotals = subgroupAccounts.collectEntries { AccountSubgroup sg, List<AccountDetail> details ->
        [(sg): details.sum(BigDecimal.ZERO) { AccountDetail d -> d.amount } as BigDecimal]
      } as Map<AccountSubgroup, BigDecimal>

      String summaryPrefix = I18n.instance.getString('incomeStatementSection.summary.prefix')
      List<IncomeStatementRow> rows = []
      Map<IncomeStatementSection, BigDecimal> sectionTotals = [:]

      IncomeStatementSection.values().each { IncomeStatementSection section ->
        if (section.computed) {
          BigDecimal computedAmount = computeSectionResult(section, sectionTotals)
          sectionTotals[section] = computedAmount
          rows << new IncomeStatementRow(
              section.name(),
              computedSectionLabel(section),
              scale(computedAmount),
              section == IncomeStatementSection.NET_RESULT ? IncomeStatementRowType.GRAND_TOTAL : IncomeStatementRowType.RESULT_LINE
          )
        } else {
          IncomeSectionBuildResult sectionBuild = buildIncomeSectionRows(section, subgroupAccounts, subgroupTotals, summaryPrefix)
          sectionTotals[section] = sectionBuild.total
          if (!sectionBuild.rows.isEmpty()) {
            rows << new IncomeStatementRow(
                section.name(),
                sectionHeaderLabel(section),
                null,
                IncomeStatementRowType.SECTION_HEADER
            )
            rows.addAll(sectionBuild.rows)
            rows << new IncomeStatementRow(
                section.name(),
                sectionTotalLabel(section, summaryPrefix),
                scale(sectionBuild.total),
                IncomeStatementRowType.SECTION_TOTAL
            )
          }
        }
      }

      BigDecimal netResult = sectionTotals[IncomeStatementSection.NET_RESULT] ?: BigDecimal.ZERO

      createResult(
          effective,
          [
              "${IncomeStatementSection.OPERATING_RESULT.displayName}: ${formatAmountLocale(scale(sectionTotals[IncomeStatementSection.OPERATING_RESULT] ?: BigDecimal.ZERO))}".toString(),
              "${IncomeStatementSection.RESULT_AFTER_FINANCIAL.displayName}: ${formatAmountLocale(scale(sectionTotals[IncomeStatementSection.RESULT_AFTER_FINANCIAL] ?: BigDecimal.ZERO))}".toString(),
              "${IncomeStatementSection.NET_RESULT.displayName}: ${formatAmountLocale(scale(netResult))}".toString()
          ],
          [I18n.instance.getString('incomeStatementSection.column.item'), i18nOrFallback('incomeStatementSection.column.closingBalance', I18n.instance.getString('incomeStatementSection.column.amount'))],
          rows.collect { IncomeStatementRow row ->
            stringRow(row.displayLabel, row.amount == null ? '' : formatAmountLocale(row.amount))
          },
          rows.collect { IncomeStatementRow ignored -> null as Long } as List<Long>,
          [typedRows: rows, result: scale(netResult)]
      )
    } as ReportResult
  }

  private Map<AccountSubgroup, List<AccountDetail>> buildIncomeAccounts(
      Map<String, AccountInfo> accountInfos,
      Map<String, Totals> periodTotals
  ) {
    Map<AccountSubgroup, List<AccountDetail>> subgroupAccounts = [:]
    accountInfos.keySet().sort().each { String accountNumber ->
      AccountInfo info = accountInfos[accountNumber]
      AccountSubgroup subgroup = info.accountSubgroup
          ? AccountSubgroup.fromDatabaseValue(info.accountSubgroup)
          : AccountSubgroup.fromAccountNumber(accountNumber)
      if (subgroup == null) {
        log.warning("Konto ${accountNumber} (${info.accountName}) saknar undergrupp och exkluderas från resultatrapporten.")
        return
      }
      String incomeAccountClass = resolveIncomeAccountClass(accountNumber, info, subgroup)
      if (!(incomeAccountClass in ['INCOME', 'EXPENSE'])) {
        return
      }
      String incomeNormalBalanceSide = resolveIncomeNormalBalanceSide(accountNumber, info, incomeAccountClass)
      Totals totals = periodTotals[accountNumber] ?: Totals.ZERO
      BigDecimal amount = signedAmount(totals.debitAmount, totals.creditAmount, incomeNormalBalanceSide)
      if (incomeAccountClass == 'EXPENSE') {
        amount = amount.negate()
      }
      if (amount == BigDecimal.ZERO) {
        return
      }
      List<AccountDetail> list = subgroupAccounts.computeIfAbsent(subgroup) { [] }
      list.add(new AccountDetail(accountNumber, info.accountName, amount))
    }
    subgroupAccounts
  }

  private IncomeSectionBuildResult buildIncomeSectionRows(
      IncomeStatementSection section,
      Map<AccountSubgroup, List<AccountDetail>> subgroupAccounts,
      Map<AccountSubgroup, BigDecimal> subgroupTotals,
      String summaryPrefix
  ) {
    List<IncomeStatementRow> sectionRows = []
    BigDecimal sectionSum = BigDecimal.ZERO
    section.subgroups.each { AccountSubgroup subgroup ->
      List<AccountDetail> accounts = subgroupAccounts[subgroup] ?: []
      if (accounts.isEmpty()) {
        return
      }
      BigDecimal subgroupTotal = subgroupTotals[subgroup] ?: BigDecimal.ZERO
      sectionRows.addAll(buildIncomeSubgroupRows(section, subgroup, accounts, subgroupTotal, summaryPrefix))
      sectionSum = sectionSum + subgroupTotal
    }
    new IncomeSectionBuildResult(sectionRows, sectionSum)
  }

  private List<IncomeStatementRow> buildIncomeSubgroupRows(
      IncomeStatementSection section,
      AccountSubgroup subgroup,
      List<AccountDetail> accounts,
      BigDecimal subgroupTotal,
      String summaryPrefix
  ) {
    List<IncomeStatementRow> subgroupRows = []
    if (shouldAddIncomeGroupHeader(section, accounts)) {
      subgroupRows << new IncomeStatementRow(
          section.name(),
          incomeSubgroupHeadingLabel(subgroup),
          null,
          IncomeStatementRowType.GROUP_HEADER
      )
    }
    accounts.each { AccountDetail detail ->
      subgroupRows << new IncomeStatementRow(
          section.name(),
          "${detail.accountNumber} ${detail.accountName}".toString(),
          scale(detail.amount),
          IncomeStatementRowType.DETAIL
      )
    }
    subgroupRows << new IncomeStatementRow(
        section.name(),
        summaryLabel(summaryPrefix, incomeSubgroupSummaryLabel(subgroup)),
        scale(subgroupTotal),
        IncomeStatementRowType.SUBTOTAL
    )
    subgroupRows
  }

  private String resolveIncomeAccountClass(String accountNumber, AccountInfo info, AccountSubgroup subgroup) {
    String normalized = info.accountClass?.trim()?.toUpperCase(Locale.ROOT)
    if (normalized in ['INCOME', 'EXPENSE']) {
      return normalized
    }
    String inferred = inferIncomeAccountClassFromAccountNumber(accountNumber)
    if (inferred != null) {
      log.info("Konto ${accountNumber} (${info.accountName}) saknar resultatklassning. Härleder ${inferred} från kontonummerprefix ${accountNumber.take(2)}.")
      return inferred
    }
    inferred = inferIncomeAccountClass(subgroup)
    if (inferred != null) {
      log.info("Konto ${accountNumber} (${info.accountName}) saknar resultatklassning. Härleder ${inferred} från undergruppen ${subgroup.name()}.")
    } else {
      log.warning("Konto ${accountNumber} (${info.accountName}) kunde inte klassificeras som INCOME eller EXPENSE – utesluts från resultatrapporten.")
    }
    inferred
  }

  private String resolveIncomeNormalBalanceSide(String accountNumber, AccountInfo info, String incomeAccountClass) {
    String normalized = info.normalBalanceSide?.trim()?.toUpperCase(Locale.ROOT)
    if (normalized) {
      return normalized
    }
    String inferred = inferNormalBalanceSide(incomeAccountClass)
    if (inferred != null) {
      log.info("Konto ${accountNumber} (${info.accountName}) saknar normal balanssida. Härleder ${inferred} från kontoklassen ${incomeAccountClass}.")
      return inferred
    }
    throw new IllegalStateException("Konto ${accountNumber} (${info.accountName}) saknar normal balanssida för rapportering.")
  }

  private static String inferIncomeAccountClass(AccountSubgroup subgroup) {
    switch (subgroup) {
      case AccountSubgroup.NET_REVENUE:
      case AccountSubgroup.INVOICED_COSTS:
      case AccountSubgroup.SECONDARY_INCOME:
      case AccountSubgroup.REVENUE_ADJUSTMENTS:
      case AccountSubgroup.CAPITALIZED_WORK:
      case AccountSubgroup.OTHER_OPERATING_INCOME:
      case AccountSubgroup.FINANCIAL_INCOME:
        return 'INCOME'
      case AccountSubgroup.RAW_MATERIALS:
      case AccountSubgroup.OTHER_EXTERNAL_COSTS:
      case AccountSubgroup.PERSONNEL_COSTS:
      case AccountSubgroup.DEPRECIATION:
      case AccountSubgroup.OTHER_OPERATING_COSTS:
      case AccountSubgroup.FINANCIAL_COSTS:
      case AccountSubgroup.APPROPRIATIONS:
      case AccountSubgroup.TAX_AND_RESULT:
        return 'EXPENSE'
      default:
        return null
    }
  }

  private static String inferIncomeAccountClassFromAccountNumber(String accountNumber) {
    inferIncomeAccountClass(AccountSubgroup.fromAccountNumber(accountNumber))
  }

  private static String resolveTrialBalanceNormalSide(String accountNumber, AccountInfo info) {
    String stored = info.normalBalanceSide?.trim()?.toUpperCase(Locale.ROOT)
    if (stored) {
      return stored
    }
    AccountSubgroup subgroup = AccountSubgroup.fromAccountNumber(accountNumber)
    if (subgroup == null) {
      throw new IllegalStateException("Konto ${accountNumber} (${info.accountName}) saknar normal balanssida för rapportering.")
    }
    if (subgroup.basGroupStart >= 10 && subgroup.basGroupEnd <= 19) {
      return 'DEBIT'
    }
    if (subgroup.basGroupStart >= 20 && subgroup.basGroupEnd <= 29) {
      return 'CREDIT'
    }
    String incomeClass = inferIncomeAccountClass(subgroup)
    String inferred = inferNormalBalanceSide(incomeClass)
    if (inferred != null) {
      return inferred
    }
    throw new IllegalStateException("Konto ${accountNumber} (${info.accountName}) saknar normal balanssida för rapportering.")
  }

  private static String inferNormalBalanceSide(String accountClass) {
    switch (accountClass) {
      case 'INCOME':
        return 'CREDIT'
      case 'EXPENSE':
        return 'DEBIT'
      default:
        return null
    }
  }

  private static String summaryLabel(String prefix, String displayName) {
    if (!displayName) {
      return prefix
    }
    "${prefix} ${displayName.substring(0, 1).toLowerCase(I18n.instance.locale)}${displayName.substring(1)}".toString()
  }

  private static String sectionHeaderLabel(IncomeStatementSection section) {
    if (section in [IncomeStatementSection.OPERATING_INCOME, IncomeStatementSection.OPERATING_EXPENSES]) {
      return section.displayName.toUpperCase(I18n.instance.locale)
    }
    section.displayName
  }

  private static String sectionTotalLabel(IncomeStatementSection section, String summaryPrefix) {
    String label = summaryLabel(summaryPrefix, section.displayName)
    if (section in [IncomeStatementSection.OPERATING_INCOME, IncomeStatementSection.OPERATING_EXPENSES]) {
      return label.toUpperCase(I18n.instance.locale)
    }
    label
  }

  private static String computedSectionLabel(IncomeStatementSection section) {
    section == IncomeStatementSection.NET_RESULT
        ? section.displayName.toUpperCase(I18n.instance.locale)
        : section.displayName
  }

  private static boolean shouldAddIncomeGroupHeader(IncomeStatementSection section, List<AccountDetail> accounts) {
    section in [IncomeStatementSection.OPERATING_INCOME, IncomeStatementSection.OPERATING_EXPENSES] && accounts.size() > 1
  }

  private static String incomeSubgroupHeadingLabel(AccountSubgroup subgroup) {
    i18nOrFallback("incomeStatementSubgroup.${subgroup.name()}.heading", subgroup.displayName)
  }

  private static String incomeSubgroupSummaryLabel(AccountSubgroup subgroup) {
    i18nOrFallback("incomeStatementSubgroup.${subgroup.name()}.summary", subgroup.displayName)
  }

  private static String i18nOrFallback(String key, String fallback) {
    I18n.instance.hasString(key) ? I18n.instance.getString(key) : fallback
  }

  private static BigDecimal computeSectionResult(
      IncomeStatementSection section,
      Map<IncomeStatementSection, BigDecimal> sectionTotals
  ) {
    switch (section) {
      case IncomeStatementSection.OPERATING_RESULT:
        return (sectionTotals[IncomeStatementSection.OPERATING_INCOME] ?: BigDecimal.ZERO) +
            (sectionTotals[IncomeStatementSection.OPERATING_EXPENSES] ?: BigDecimal.ZERO)
      case IncomeStatementSection.RESULT_AFTER_FINANCIAL:
        return (sectionTotals[IncomeStatementSection.OPERATING_RESULT] ?: BigDecimal.ZERO) +
            (sectionTotals[IncomeStatementSection.FINANCIAL_ITEMS] ?: BigDecimal.ZERO)
      case IncomeStatementSection.RESULT_AFTER_EXTRAORDINARY:
        return sectionTotals[IncomeStatementSection.RESULT_AFTER_FINANCIAL] ?: BigDecimal.ZERO
      case IncomeStatementSection.PROFIT_BEFORE_TAX:
        return (sectionTotals[IncomeStatementSection.RESULT_AFTER_EXTRAORDINARY] ?: BigDecimal.ZERO) +
            (sectionTotals[IncomeStatementSection.APPROPRIATIONS] ?: BigDecimal.ZERO)
      case IncomeStatementSection.NET_RESULT:
        return (sectionTotals[IncomeStatementSection.PROFIT_BEFORE_TAX] ?: BigDecimal.ZERO) +
            (sectionTotals[IncomeStatementSection.TAX] ?: BigDecimal.ZERO)
      default:
        throw new IllegalStateException("Okänd beräknad sektion: ${section}")
    }
  }

  @SuppressWarnings('AbcMetric')
  private ReportResult buildBalanceSheetReport(EffectiveSelection effective) {
    databaseService.withSql { Sql sql ->
      Map<String, AccountInfo> accountInfos = loadAccountInfos(sql, effective.companyId)
      Map<String, BigDecimal> openingBalances = loadOpeningBalances(sql, effective.selection.fiscalYearId)
      Map<String, BigDecimal> movements = loadSignedMovements(sql, effective.selection.fiscalYearId, effective.fiscalYear.startDate, effective.endDate)

      Map<String, BigDecimal> closingBalances = buildClosingBalances(accountInfos, openingBalances, movements)
      List<String> skippedAccounts = []
      Map<AccountSubgroup, List<AccountDetail>> subgroupAccounts = buildSubgroupAccounts(accountInfos, closingBalances, skippedAccounts)
      Map<AccountSubgroup, BigDecimal> subgroupTotals = subgroupAccounts.collectEntries { AccountSubgroup sg, List<AccountDetail> details ->
        [(sg): details.sum(BigDecimal.ZERO) { AccountDetail d -> d.amount } as BigDecimal]
      } as Map<AccountSubgroup, BigDecimal>

      List<BalanceSheetRow> rows = []
      Map<BalanceSheetSection, BigDecimal> sectionTotals = [:]

      BalanceSheetSection.values().each { BalanceSheetSection section ->
        if (section.computed) {
          BigDecimal computedAmount = computeBalanceSheetTotal(section, sectionTotals)
          sectionTotals[section] = computedAmount
          rows << new BalanceSheetRow(section.name(), null, null, scale(computedAmount), section.displayName, true)
        } else {
          BigDecimal sectionSum = BigDecimal.ZERO
          section.subgroups.each { AccountSubgroup subgroup ->
            List<AccountDetail> accounts = subgroupAccounts[subgroup] ?: []
            BigDecimal sgTotal = subgroupTotals[subgroup] ?: BigDecimal.ZERO
            accounts.each { AccountDetail detail ->
              rows << new BalanceSheetRow(section.name(), detail.accountNumber, detail.accountName, scale(detail.amount), null, false)
            }
            if (accounts.size() > 0 && sgTotal != BigDecimal.ZERO) {
              rows << new BalanceSheetRow(section.name(), null, null, scale(sgTotal), subgroup.displayName, true)
            }
            sectionSum = sectionSum + sgTotal
          }
          sectionTotals[section] = sectionSum
          if (sectionSum != BigDecimal.ZERO) {
            rows << new BalanceSheetRow(section.name(), null, null, scale(sectionSum), section.displayName, true)
          }
        }
      }

      BigDecimal assetTotal = sectionTotals[BalanceSheetSection.TOTAL_ASSETS] ?: BigDecimal.ZERO
      BigDecimal equityAndLiabilitiesTotal = sectionTotals[BalanceSheetSection.TOTAL_EQUITY_AND_LIABILITIES] ?: BigDecimal.ZERO

      List<String> summaryLines = [
          "${BalanceSheetSection.TOTAL_ASSETS.displayName}: ${formatAmountLocale(scale(assetTotal))}".toString(),
          "${BalanceSheetSection.TOTAL_EQUITY_AND_LIABILITIES.displayName}: ${formatAmountLocale(scale(equityAndLiabilitiesTotal))}".toString()
      ]
      if (skippedAccounts) {
        summaryLines.add(I18n.instance.format('balanceSheetReport.summary.unmappedAccounts', skippedAccounts.join(', ')))
      }

      createResult(
          effective,
          summaryLines,
          [I18n.instance.getString('balanceSheetSection.column.item'), I18n.instance.getString('balanceSheetSection.column.amount')],
          rows.collect { BalanceSheetRow row ->
            String label = row.accountNumber
                ? "${row.accountNumber} ${row.accountName}"
                : (row.subgroupDisplayName ?: row.section)
            stringRow(label, formatAmountLocale(row.amount))
          },
          rows.collect { BalanceSheetRow ignored -> null as Long } as List<Long>,
          [typedRows: rows, assetTotal: scale(assetTotal), equityAndLiabilitiesTotal: scale(equityAndLiabilitiesTotal)]
      )
    } as ReportResult
  }

  private Map<String, BigDecimal> buildClosingBalances(
      Map<String, AccountInfo> accountInfos,
      Map<String, BigDecimal> openingBalances,
      Map<String, BigDecimal> movements
  ) {
    Map<String, BigDecimal> closingBalances = [:]
    accountInfos.each { String accountNumber, AccountInfo info ->
      if (!(info.accountClass in ['ASSET', 'LIABILITY', 'EQUITY'])) {
        return
      }
      BigDecimal amount = (openingBalances[accountNumber] ?: BigDecimal.ZERO) + (movements[accountNumber] ?: BigDecimal.ZERO)
      if (amount != BigDecimal.ZERO) {
        closingBalances[accountNumber] = amount
      }
    }
    closingBalances
  }

  private Map<AccountSubgroup, List<AccountDetail>> buildSubgroupAccounts(
      Map<String, AccountInfo> accountInfos,
      Map<String, BigDecimal> closingBalances,
      List<String> skippedAccounts
  ) {
    Map<AccountSubgroup, List<AccountDetail>> subgroupAccounts = [:]
    closingBalances.keySet().sort().each { String accountNumber ->
      AccountInfo info = accountInfos[accountNumber]
      AccountSubgroup subgroup = info.accountSubgroup
          ? AccountSubgroup.fromDatabaseValue(info.accountSubgroup)
          : AccountSubgroup.fromAccountNumber(accountNumber)
      if (subgroup == null) {
        log.warning("Konto ${accountNumber} (${info.accountName}) saknar undergrupp och exkluderas från balansrapporten.")
        skippedAccounts.add("${accountNumber} ${info.accountName}".toString())
        return
      }
      subgroup = resolveBalanceSheetSubgroup(subgroup, info.accountClass)
      BigDecimal amount = closingBalances[accountNumber]
      List<AccountDetail> list = subgroupAccounts.computeIfAbsent(subgroup) { [] }
      list.add(new AccountDetail(accountNumber, info.accountName, amount))
    }
    subgroupAccounts
  }

  private static AccountSubgroup resolveBalanceSheetSubgroup(AccountSubgroup subgroup, String accountClass) {
    BalanceSheetSection section = BalanceSheetSection.findSectionForSubgroup(subgroup)
    if (section == null) {
      throw new IllegalStateException("AccountSubgroup ${subgroup.name()} har ingen mappning till BalanceSheetSection")
    }
    boolean accountIsAsset = accountClass == 'ASSET'
    if (accountIsAsset && !section.assetSide) {
      return AccountSubgroup.OTHER_CURRENT_RECEIVABLES
    }
    if (!accountIsAsset && section.assetSide) {
      return AccountSubgroup.OTHER_CURRENT_LIABILITIES
    }
    subgroup
  }

  private static BigDecimal computeBalanceSheetTotal(
      BalanceSheetSection section,
      Map<BalanceSheetSection, BigDecimal> sectionTotals
  ) {
    switch (section) {
      case BalanceSheetSection.TOTAL_ASSETS:
        return (sectionTotals[BalanceSheetSection.FIXED_ASSETS] ?: BigDecimal.ZERO) +
            (sectionTotals[BalanceSheetSection.CURRENT_ASSETS] ?: BigDecimal.ZERO)
      case BalanceSheetSection.TOTAL_EQUITY_AND_LIABILITIES:
        return (sectionTotals[BalanceSheetSection.EQUITY] ?: BigDecimal.ZERO) +
            (sectionTotals[BalanceSheetSection.UNTAXED_RESERVES] ?: BigDecimal.ZERO) +
            (sectionTotals[BalanceSheetSection.PROVISIONS] ?: BigDecimal.ZERO) +
            (sectionTotals[BalanceSheetSection.LONG_TERM_LIABILITIES] ?: BigDecimal.ZERO) +
            (sectionTotals[BalanceSheetSection.CURRENT_LIABILITIES] ?: BigDecimal.ZERO)
      default:
        throw new IllegalStateException("Okänd beräknad sektion: ${section}")
    }
  }

  private ReportResult buildTransactionReport(EffectiveSelection effective) {
    List<TransactionReportRow> rows = databaseService.withSql { Sql sql ->
      loadPostingLines(sql, effective.selection.fiscalYearId, effective.startDate, effective.endDate)
          .sort { PostingLine line ->
            [line.accountingDate, line.voucherNumber ?: '', line.voucherId, line.lineIndex]
          }.collect { PostingLine line ->
            new TransactionReportRow(
                line.voucherId,
                line.accountingDate,
                line.voucherNumber,
                line.accountNumber,
                line.accountName,
                line.voucherDescription,
                line.lineDescription,
                line.debitAmount,
                line.creditAmount,
                line.status
            )
          }
    }
    BigDecimal debitTotal = rows.sum(BigDecimal.ZERO) { TransactionReportRow row -> row.debitAmount } as BigDecimal
    BigDecimal creditTotal = rows.sum(BigDecimal.ZERO) { TransactionReportRow row -> row.creditAmount } as BigDecimal
    createResult(
        effective,
        [
            I18n.instance.format('transactionReport.summary.count', rows.size()),
            I18n.instance.format('transactionReport.summary.debitTotal', formatAmount(scale(debitTotal))),
            I18n.instance.format('transactionReport.summary.creditTotal', formatAmount(scale(creditTotal)))
        ],
        transactionReportHeaders(),
        rows.collect { TransactionReportRow row ->
          stringRow(
              row.accountingDate.toString(),
              row.voucherNumber,
              row.accountNumber,
              row.accountName,
              row.voucherDescription,
              row.lineDescription ?: '',
              formatAmount(row.debitAmount),
              formatAmount(row.creditAmount),
              row.status
          )
        },
        rows.collect { TransactionReportRow row -> row.voucherId },
        [typedRows: rows, lead: I18n.instance.getString('report.transactionReport.lead')]
    )
  }

  private static List<String> transactionReportHeaders() {
    [
        I18n.instance.getString('transactionReport.column.date'),
        I18n.instance.getString('transactionReport.column.voucher'),
        I18n.instance.getString('transactionReport.column.account'),
        I18n.instance.getString('transactionReport.column.accountName'),
        I18n.instance.getString('transactionReport.column.voucherText'),
        I18n.instance.getString('transactionReport.column.lineText'),
        I18n.instance.getString('transactionReport.column.debit'),
        I18n.instance.getString('transactionReport.column.credit'),
        I18n.instance.getString('transactionReport.column.status')
    ]
  }

  @SuppressWarnings('AbcMetric')
  private ReportResult buildVatReport(EffectiveSelection effective) {
    databaseService.withSql { Sql sql ->
      Map<VatCode, VatBucket> buckets = [:]
      VatReportSupport.loadSeeds(sql, effective.selection.fiscalYearId, effective.startDate, effective.endDate, true)
          .each { VatReportSupport.VatSeed seed ->
        VatBucket bucket = buckets.computeIfAbsent(seed.vatCode) { VatCode ignored ->
          new VatBucket()
        }
        bucket.baseAmount = scale(bucket.baseAmount + seed.baseAmount)
        bucket.postedOutputVat = scale(bucket.postedOutputVat + seed.postedOutputVat)
        bucket.postedInputVat = scale(bucket.postedInputVat + seed.postedInputVat)
        bucket.outputPostingCount += seed.outputPostingCount
        bucket.inputPostingCount += seed.inputPostingCount
      }
      List<VatReportEntry> rows = buckets.entrySet().sort { Map.Entry<VatCode, VatBucket> entry -> entry.key.ordinal() }.collect { Map.Entry<VatCode, VatBucket> entry ->
        VatCode vatCode = entry.key
        VatBucket bucket = entry.value
        BigDecimal computedOutputVat = scale(bucket.baseAmount * vatCode.outputRate)
        BigDecimal computedInputVat = scale(bucket.baseAmount * vatCode.inputRate)
        new VatReportEntry(
            vatCode.name(),
            vatCode.displayName,
            bucket.baseAmount,
            bucket.outputPostingCount > 0 ? bucket.postedOutputVat : computedOutputVat,
            bucket.inputPostingCount > 0 ? bucket.postedInputVat : computedInputVat
        )
      }
      BigDecimal outputTotal = rows.sum(BigDecimal.ZERO) { VatReportEntry row -> row.outputVatAmount } as BigDecimal
      BigDecimal inputTotal = rows.sum(BigDecimal.ZERO) { VatReportEntry row -> row.inputVatAmount } as BigDecimal
      BigDecimal netTotal = scale(outputTotal - inputTotal)
      createResult(
          effective,
          [
              I18n.instance.format('vatReport.summary.outputVat', formatAmount(scale(outputTotal))),
              I18n.instance.format('vatReport.summary.inputVat', formatAmount(scale(inputTotal))),
              I18n.instance.format('vatReport.summary.net', formatAmount(netTotal))
          ],
          [
              I18n.instance.getString('vatReport.column.code'),
              I18n.instance.getString('vatReport.column.label'),
              I18n.instance.getString('vatReport.column.base'),
              I18n.instance.getString('vatReport.column.outputVat'),
              I18n.instance.getString('vatReport.column.inputVat')
          ],
          rows.collect { VatReportEntry row ->
            stringRow(row.vatCode, row.label, formatAmount(row.baseAmount), formatAmount(row.outputVatAmount), formatAmount(row.inputVatAmount))
          },
          rows.collect { VatReportEntry ignored -> null as Long } as List<Long>,
          [
              typedRows: rows,
              outputTotal: scale(outputTotal),
              inputTotal: scale(inputTotal),
              netTotal: netTotal,
              outputVatLabel: I18n.instance.getString('report.vatReport.metric.outputVat'),
              inputVatLabel: I18n.instance.getString('report.vatReport.metric.inputVat'),
              netLabel: I18n.instance.getString('report.vatReport.metric.net')
          ]
      )
    } as ReportResult
  }

  private EffectiveSelection resolveSelection(ReportSelection selection) {
    if (selection?.reportType == null || selection.fiscalYearId == null) {
      throw new IllegalArgumentException('Rapporttyp och räkenskapsår är obligatoriska.')
    }
    FiscalYear fiscalYear = fiscalYearService.findById(selection.fiscalYearId)
    if (fiscalYear == null) {
      throw new IllegalArgumentException("Okänt räkenskapsår: ${selection.fiscalYearId}")
    }
    AccountingPeriod period = selection.accountingPeriodId == null ? null : accountingPeriodService.findPeriod(selection.accountingPeriodId)
    if (period != null && period.fiscalYearId != fiscalYear.id) {
      throw new IllegalArgumentException('Vald redovisningsperiod tillhör inte valt räkenskapsår.')
    }
    LocalDate startDate = period?.startDate ?: (selection.startDate ?: fiscalYear.startDate)
    LocalDate endDate = period?.endDate ?: (selection.endDate ?: fiscalYear.endDate)
    if (startDate.isBefore(fiscalYear.startDate) || endDate.isAfter(fiscalYear.endDate) || endDate.isBefore(startDate)) {
      throw new IllegalArgumentException('Rapportintervallet måste ligga inom räkenskapsåret.')
    }
    long companyId = resolveCompanyId(fiscalYear.id)
    String selectionLabel = period == null
        ? I18n.instance.format('report.selection.interval', startDate.toString(), endDate.toString())
        : I18n.instance.format('report.selection.period', period.periodName, startDate.toString(), endDate.toString())
    new EffectiveSelection(selection, fiscalYear, companyId, startDate, endDate, selectionLabel)
  }

  private static Map<String, AccountInfo> loadAccountInfos(Sql sql, long companyId) {
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

  private static Map<String, BigDecimal> loadOpeningBalances(Sql sql, long fiscalYearId) {
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

  private static Map<String, BigDecimal> loadSignedMovements(Sql sql, long fiscalYearId, LocalDate startDate, LocalDate endDate) {
    if (endDate.isBefore(startDate)) {
      return [:]
    }
    Map<String, BigDecimal> movements = [:]
    sql.rows('''
        select a.account_number as accountNumber,
               a.normal_balance_side as normalBalanceSide,
               sum(vl.debit_amount) as debitAmount,
               sum(vl.credit_amount) as creditAmount
          from voucher v
          join voucher_line vl on vl.voucher_id = v.id
          join account a on a.id = vl.account_id
         where v.fiscal_year_id = ?
           and v.status in ('ACTIVE', 'CORRECTION')
           and v.accounting_date between ? and ?
         group by a.account_number, a.normal_balance_side
    ''', [fiscalYearId, Date.valueOf(startDate), Date.valueOf(endDate)]).each { GroovyRowResult row ->
      movements.put(
          row.get('accountNumber') as String,
          signedAmount(
              new BigDecimal(row.get('debitAmount').toString()),
              new BigDecimal(row.get('creditAmount').toString()),
              row.get('normalBalanceSide') as String
          )
      )
    }
    movements
  }

  private static Map<String, Totals> loadPeriodTotals(Sql sql, long fiscalYearId, LocalDate startDate, LocalDate endDate) {
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

  private static List<PostingLine> loadPostingLines(Sql sql, long fiscalYearId, LocalDate startDate, LocalDate endDate) {
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
          row.get('normalBalanceSide') as String,
          row.get('lineDescription') as String,
          scale(new BigDecimal(row.get('debitAmount').toString())),
          scale(new BigDecimal(row.get('creditAmount').toString()))
      )
    }
  }

  private ReportResult createResult(
      EffectiveSelection effective,
      List<String> summaryLines,
      List<String> headers,
      List<List<String>> tableRows,
      List<Long> rowVoucherIds,
      Map<String, Object> extraModel
  ) {
    Map<String, Object> templateModel = [
        title         : effective.selection.reportType.displayName,
        selectionLabel: effective.selectionLabel,
        reportType    : effective.selection.reportType,
        startDate     : effective.startDate,
        endDate       : effective.endDate,
        summaryLines  : summaryLines,
        tableHeaders  : headers,
        tableRows     : tableRows,
        htmlLang      : I18n.instance.getString('report.common.htmlLang'),
        orgNumberLabel: I18n.instance.getString('report.common.orgNumber')
    ] + (extraModel ?: [:])
    new ReportResult(
        effective.selection.reportType,
        effective.selection.reportType.displayName,
        effective.selectionLabel,
        effective.selection.fiscalYearId,
        effective.selection.accountingPeriodId,
        effective.startDate,
        effective.endDate,
        summaryLines,
        headers,
        tableRows,
        rowVoucherIds,
        templateModel
    )
  }

  private long resolveCompanyId(long fiscalYearId) {
    databaseService.withSql { Sql sql ->
      CompanyService.resolveFromFiscalYear(sql, fiscalYearId)
    }
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
    (amount ?: BigDecimal.ZERO).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)
  }

  private static String formatAmount(BigDecimal amount) {
    scale(amount).toPlainString()
  }

  // One cached DecimalFormat per thread per locale; avoids repeated NumberFormat.getNumberInstance()
  // calls (expensive) while keeping thread-safety (DecimalFormat is not thread-safe).
  private static final ThreadLocal<Map<Locale, DecimalFormat>> AMOUNT_FORMATTERS =
      ThreadLocal.withInitial { [:] }

  private static String formatAmountLocale(BigDecimal amount) {
    Locale locale = I18n.instance.locale
    DecimalFormat formatter = AMOUNT_FORMATTERS.get().computeIfAbsent(locale) { Locale loc ->
      DecimalFormat fmt = (DecimalFormat) NumberFormat.getNumberInstance(loc)
      fmt.minimumFractionDigits = AMOUNT_SCALE
      fmt.maximumFractionDigits = AMOUNT_SCALE
      // The locale-provided minus sign (U+2212) is missing from openhtmltopdf's default
      // WinAnsi PDF fonts and renders as '#'. ASCII hyphen-minus avoids that and is
      // equally valid in CSV and Excel output.
      DecimalFormatSymbols symbols = fmt.decimalFormatSymbols
      symbols.minusSign = (char) '-'
      fmt.decimalFormatSymbols = symbols
      fmt
    }
    formatter.format(scale(amount))
  }

  private static List<String> stringRow(String... values) {
    values.toList()
  }

  private static final class EffectiveSelection {

    final ReportSelection selection
    final FiscalYear fiscalYear
    final long companyId
    final LocalDate startDate
    final LocalDate endDate
    final String selectionLabel

    private EffectiveSelection(
        ReportSelection selection,
        FiscalYear fiscalYear,
        long companyId,
        LocalDate startDate,
        LocalDate endDate,
        String selectionLabel
    ) {
      this.selection = new ReportSelection(
          selection.reportType,
          selection.fiscalYearId,
          selection.accountingPeriodId,
          startDate,
          endDate
      )
      this.fiscalYear = fiscalYear
      this.companyId = companyId
      this.startDate = startDate
      this.endDate = endDate
      this.selectionLabel = selectionLabel
    }
  }

  @Canonical
  private static final class PostingLine {

    Long voucherId
    LocalDate accountingDate
    String voucherNumber
    String voucherDescription
    String status
    int lineIndex
    String accountNumber
    String accountName
    String accountClass
    String normalBalanceSide
    String lineDescription
    BigDecimal debitAmount
    BigDecimal creditAmount

    BigDecimal signedAmount() {
      ReportDataService.signedAmount(debitAmount, creditAmount, normalBalanceSide)
    }
  }

  @Canonical
  private static final class AccountInfo {

    Long accountId
    String accountName
    String accountClass
    String normalBalanceSide
    String accountSubgroup
  }

  @Canonical
  private static final class Totals {

    static final Totals ZERO = new Totals(BigDecimal.ZERO.setScale(AMOUNT_SCALE), BigDecimal.ZERO.setScale(AMOUNT_SCALE))

    BigDecimal debitAmount
    BigDecimal creditAmount
  }

  @Canonical
  private static final class AccountDetail {
    String accountNumber
    String accountName
    BigDecimal amount
  }

  @Canonical
  private static final class IncomeSectionBuildResult {
    List<IncomeStatementRow> rows
    BigDecimal total
  }

  private static final class VatBucket {

    BigDecimal baseAmount = BigDecimal.ZERO.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)
    BigDecimal postedOutputVat = BigDecimal.ZERO.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)
    BigDecimal postedInputVat = BigDecimal.ZERO.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)
    int outputPostingCount
    int inputPostingCount
  }
}
