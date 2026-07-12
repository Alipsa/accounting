package se.alipsa.accounting.service

import static se.alipsa.accounting.service.ReportAccountSupport.inferIncomeAccountClass
import static se.alipsa.accounting.service.ReportAccountSupport.inferIncomeAccountClassFromAccountNumber
import static se.alipsa.accounting.service.ReportAccountSupport.inferNormalBalanceSide
import static se.alipsa.accounting.service.ReportAccountSupport.shouldExcludeFromIncomeStatement

import groovy.sql.GroovyRowResult
import groovy.sql.Sql

import se.alipsa.accounting.domain.AccountSubgroup
import se.alipsa.accounting.domain.AccountingPeriod
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.VatCode
import se.alipsa.accounting.domain.report.BalanceSheetRow
import se.alipsa.accounting.domain.report.BalanceSheetRowType
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
import se.alipsa.accounting.support.AmountFormatter
import se.alipsa.accounting.support.I18n

import java.math.RoundingMode
import java.sql.Date
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
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
          formatAmountLocale(row.debitAmount, effective.locale),
          formatAmountLocale(row.creditAmount, effective.locale)
      )
    }
    BigDecimal debitTotal = rows.sum(BigDecimal.ZERO) { VoucherListRow row -> row.debitAmount } as BigDecimal
    BigDecimal creditTotal = rows.sum(BigDecimal.ZERO) { VoucherListRow row -> row.creditAmount } as BigDecimal
    createResult(
        effective,
        [
            I18n.instance.format('voucherListReport.summary.count', rows.size()),
            I18n.instance.format('voucherListReport.summary.debitTotal', formatAmountLocale(scale(debitTotal), effective.locale)),
            I18n.instance.format('voucherListReport.summary.creditTotal', formatAmountLocale(scale(creditTotal), effective.locale))
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
      Map<String, AccountInfo> accountInfos = ReportSqlLoader.loadAccountInfos(sql, effective.companyId)
      Map<String, BigDecimal> openingBalances = ReportSqlLoader.loadOpeningBalances(sql, effective.selection.fiscalYearId)
      Map<String, BigDecimal> priorBalances = effective.startDate.isAfter(effective.fiscalYear.startDate)
          ? ReportSqlLoader.loadSignedMovements(sql, effective.selection.fiscalYearId, effective.fiscalYear.startDate, effective.startDate.minusDays(1))
          : [:]
      List<PostingLine> postingLines = ReportSqlLoader.loadPostingLines(sql, effective.selection.fiscalYearId, effective.startDate, effective.endDate)
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
            formatAmountLocale(row.debitAmount, effective.locale),
            formatAmountLocale(row.creditAmount, effective.locale),
            formatAmountLocale(row.balance, effective.locale)
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
      Map<String, AccountInfo> accountInfos = ReportSqlLoader.loadAccountInfos(sql, effective.companyId)
      Map<String, BigDecimal> openingBalances = ReportSqlLoader.loadOpeningBalances(sql, effective.selection.fiscalYearId)
      Map<String, BigDecimal> priorBalances = effective.startDate.isAfter(effective.fiscalYear.startDate)
          ? ReportSqlLoader.loadSignedMovements(sql, effective.selection.fiscalYearId, effective.fiscalYear.startDate, effective.startDate.minusDays(1))
          : [:]
      Map<String, Totals> periodTotals = ReportSqlLoader.loadPeriodTotals(sql, effective.selection.fiscalYearId, effective.startDate, effective.endDate)
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
              I18n.instance.format('trialBalanceReport.summary.openingBalance', formatAmountLocale(scale(openingTotal), effective.locale)),
              I18n.instance.format('trialBalanceReport.summary.periodDebit', formatAmountLocale(scale(debitTotal), effective.locale)),
              I18n.instance.format('trialBalanceReport.summary.periodCredit', formatAmountLocale(scale(creditTotal), effective.locale)),
              I18n.instance.format('trialBalanceReport.summary.closingBalance', formatAmountLocale(scale(closingTotal), effective.locale))
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
                formatAmountLocale(row.openingBalance, effective.locale),
                formatAmountLocale(row.debitAmount, effective.locale),
                formatAmountLocale(row.creditAmount, effective.locale),
                formatAmountLocale(row.closingBalance, effective.locale)
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
      IncomeStatementBuildResult buildResult = buildIncomeStatementRows(sql, effective)
      BigDecimal netResult = buildResult.sectionTotals[IncomeStatementSection.NET_RESULT]?.yearToDateAmount ?: BigDecimal.ZERO

      createResult(
          effective,
          incomeStatementSummaryLines(buildResult.sectionTotals, effective.locale),
          incomeStatementHeaders(),
          incomeStatementTableRows(buildResult.rows, effective.locale),
          buildResult.rows.collect { IncomeStatementRow ignored -> null as Long } as List<Long>,
          [
              typedRows: buildResult.rows,
              result: scale(netResult),
              comparisonFiscalYear: buildResult.comparisonFiscalYear,
              amountColumnLabel: I18n.instance.getString('incomeStatementSection.column.amount')
          ]
      )
    } as ReportResult
  }

  private IncomeStatementBuildResult buildIncomeStatementRows(Sql sql, EffectiveSelection effective) {
    Map<String, AccountInfo> accountInfos = ReportSqlLoader.loadAccountInfos(sql, effective.companyId)
    Map<String, Totals> periodTotals = ReportSqlLoader.loadPeriodTotals(sql, effective.selection.fiscalYearId, effective.startDate, effective.endDate)
    Map<String, Totals> yearToDateTotals = ReportSqlLoader.loadPeriodTotals(
        sql,
        effective.selection.fiscalYearId,
        effective.fiscalYear.startDate,
        effective.endDate
    )
    FiscalYear comparisonYear = findPreviousFiscalYear(sql, effective)
    Map<String, Totals> previousYearToDateTotals = comparisonYear == null
        ? [:]
        : ReportSqlLoader.loadPeriodTotals(
            sql,
            comparisonYear.id,
            comparisonYear.startDate,
            comparisonEndDate(effective, comparisonYear)
        )
    Map<AccountSubgroup, List<IncomeStatementDetail>> subgroupAccounts = buildIncomeAccounts(
        accountInfos,
        periodTotals,
        yearToDateTotals,
        previousYearToDateTotals
    )
    Map<AccountSubgroup, IncomeStatementDetail> subgroupTotals = subgroupAccounts.collectEntries { AccountSubgroup sg, List<IncomeStatementDetail> details ->
      [(sg): sumIncomeStatementDetails(details, null, null)]
    } as Map<AccountSubgroup, IncomeStatementDetail>

    IncomeStatementBuildResult buildResult = buildIncomeStatementRows(subgroupAccounts, subgroupTotals)
    buildResult.comparisonFiscalYear = comparisonYear
    buildResult
  }

  private IncomeStatementBuildResult buildIncomeStatementRows(
      Map<AccountSubgroup, List<IncomeStatementDetail>> subgroupAccounts,
      Map<AccountSubgroup, IncomeStatementDetail> subgroupTotals
  ) {
    String summaryPrefix = I18n.instance.getString('incomeStatementSection.summary.prefix')
    List<IncomeStatementRow> rows = []
    Map<IncomeStatementSection, IncomeStatementDetail> sectionTotals = [:]

    IncomeStatementSection.values().each { IncomeStatementSection section ->
      if (section.computed) {
        IncomeStatementDetail computedAmount = computeSectionResult(section, sectionTotals)
        sectionTotals[section] = computedAmount
        rows << incomeStatementRow(
            section.name(),
            computedSectionLabel(section),
            computedAmount,
            section == IncomeStatementSection.NET_RESULT ? IncomeStatementRowType.GRAND_TOTAL : IncomeStatementRowType.RESULT_LINE
        )
      } else {
        IncomeSectionBuildResult sectionBuild = buildIncomeSectionRows(section, subgroupAccounts, subgroupTotals, summaryPrefix)
        sectionTotals[section] = sectionBuild.total
        if (!sectionBuild.rows.isEmpty()) {
          rows << incomeStatementRow(
              section.name(),
              sectionHeaderLabel(section),
              null,
              IncomeStatementRowType.SECTION_HEADER
          )
          rows.addAll(sectionBuild.rows)
          rows << incomeStatementRow(
              section.name(),
              sectionTotalLabel(section, summaryPrefix),
              sectionBuild.total,
              IncomeStatementRowType.SECTION_TOTAL
          )
        }
      }
    }

    BigDecimal periodRevenue = sectionTotals[IncomeStatementSection.OPERATING_INCOME]?.periodAmount ?: BigDecimal.ZERO
    BigDecimal yearToDateRevenue = sectionTotals[IncomeStatementSection.OPERATING_INCOME]?.yearToDateAmount ?: BigDecimal.ZERO
    enrichIncomeStatementPercentages(rows, periodRevenue, yearToDateRevenue)
    new IncomeStatementBuildResult(rows, sectionTotals, null)
  }

  private static List<String> incomeStatementSummaryLines(
      Map<IncomeStatementSection, IncomeStatementDetail> sectionTotals,
      Locale locale
  ) {
    BigDecimal operatingResult = sectionTotals[IncomeStatementSection.OPERATING_RESULT]?.yearToDateAmount ?: BigDecimal.ZERO
    BigDecimal resultAfterFinancial = sectionTotals[IncomeStatementSection.RESULT_AFTER_FINANCIAL]?.yearToDateAmount ?: BigDecimal.ZERO
    BigDecimal netResult = sectionTotals[IncomeStatementSection.NET_RESULT]?.yearToDateAmount ?: BigDecimal.ZERO
    [
        "${IncomeStatementSection.OPERATING_RESULT.displayName}: ${formatAmountLocale(scale(operatingResult), locale)}".toString(),
        "${IncomeStatementSection.RESULT_AFTER_FINANCIAL.displayName}: ${formatAmountLocale(scale(resultAfterFinancial), locale)}".toString(),
        "${IncomeStatementSection.NET_RESULT.displayName}: ${formatAmountLocale(scale(netResult), locale)}".toString()
    ]
  }

  private static List<String> incomeStatementHeaders() {
    [
        I18n.instance.getString('incomeStatementSection.column.item'),
        I18n.instance.getString('incomeStatementSection.column.period'),
        I18n.instance.getString('incomeStatementSection.column.revenueShare'),
        i18nOrFallback('incomeStatementSection.column.closingBalance', I18n.instance.getString('incomeStatementSection.column.amount')),
        I18n.instance.getString('incomeStatementSection.column.revenueShare'),
        I18n.instance.getString('incomeStatementSection.column.previousYear'),
        I18n.instance.getString('incomeStatementSection.column.comparison')
    ]
  }

  private static List<List<String>> incomeStatementTableRows(List<IncomeStatementRow> rows, Locale locale) {
    rows.collect { IncomeStatementRow row ->
      stringRow(
          row.displayLabel,
          formatNullableAmount(row.periodAmount, locale),
          formatNullableAmount(row.periodRevenueShare, locale),
          formatNullableAmount(row.yearToDateAmount, locale),
          formatNullableAmount(row.yearToDateRevenueShare, locale),
          formatNullableAmount(row.previousYearToDateAmount, locale),
          formatNullableAmount(row.previousComparison, locale)
      )
    }
  }

  private Map<AccountSubgroup, List<IncomeStatementDetail>> buildIncomeAccounts(
      Map<String, AccountInfo> accountInfos,
      Map<String, Totals> periodTotals,
      Map<String, Totals> yearToDateTotals,
      Map<String, Totals> previousYearToDateTotals
  ) {
    Map<AccountSubgroup, List<IncomeStatementDetail>> subgroupAccounts = [:]
    accountInfos.keySet().sort().each { String accountNumber ->
      AccountInfo info = accountInfos[accountNumber]
      AccountSubgroup subgroup = info.accountSubgroup
          ? AccountSubgroup.fromDatabaseValue(info.accountSubgroup)
          : AccountSubgroup.fromAccountNumber(accountNumber)
      if (subgroup == null) {
        log.warning("Konto ${accountNumber} (${info.accountName}) saknar undergrupp och exkluderas från resultatrapporten.")
        return
      }
      if (shouldExcludeFromIncomeStatement(accountNumber, subgroup)) {
        return
      }
      String incomeAccountClass = resolveIncomeAccountClass(accountNumber, info, subgroup)
      if (!(incomeAccountClass in ['INCOME', 'EXPENSE'])) {
        return
      }
      String incomeNormalBalanceSide = resolveIncomeNormalBalanceSide(accountNumber, info, incomeAccountClass)
      IncomeStatementDetail detail = new IncomeStatementDetail(
          accountNumber,
          info.accountName,
          incomeAmount(periodTotals[accountNumber] ?: Totals.ZERO, incomeNormalBalanceSide, incomeAccountClass),
          incomeAmount(yearToDateTotals[accountNumber] ?: Totals.ZERO, incomeNormalBalanceSide, incomeAccountClass),
          previousYearToDateTotals
              ? incomeAmount(previousYearToDateTotals[accountNumber] ?: Totals.ZERO, incomeNormalBalanceSide, incomeAccountClass)
              : null
      )
      if (!hasIncomeAmount(detail)) {
        return
      }
      List<IncomeStatementDetail> list = subgroupAccounts.computeIfAbsent(subgroup) { [] }
      list.add(detail)
    }
    subgroupAccounts
  }

  private IncomeSectionBuildResult buildIncomeSectionRows(
      IncomeStatementSection section,
      Map<AccountSubgroup, List<IncomeStatementDetail>> subgroupAccounts,
      Map<AccountSubgroup, IncomeStatementDetail> subgroupTotals,
      String summaryPrefix
  ) {
    List<IncomeStatementRow> sectionRows = []
    List<IncomeStatementDetail> sectionDetails = []
    section.subgroups.each { AccountSubgroup subgroup ->
      List<IncomeStatementDetail> accounts = subgroupAccounts[subgroup] ?: []
      if (accounts.isEmpty()) {
        return
      }
      IncomeStatementDetail subgroupTotal = subgroupTotals[subgroup] ?: zeroIncomeStatementDetail()
      sectionRows.addAll(buildIncomeSubgroupRows(section, subgroup, accounts, subgroupTotal, summaryPrefix))
      sectionDetails.addAll(accounts)
    }
    new IncomeSectionBuildResult(sectionRows, sumIncomeStatementDetails(sectionDetails, null, section.displayName))
  }

  private List<IncomeStatementRow> buildIncomeSubgroupRows(
      IncomeStatementSection section,
      AccountSubgroup subgroup,
      List<IncomeStatementDetail> accounts,
      IncomeStatementDetail subgroupTotal,
      String summaryPrefix
  ) {
    List<IncomeStatementRow> subgroupRows = []
    if (shouldAddIncomeGroupHeader(section, accounts)) {
      subgroupRows << incomeStatementRow(
          section.name(),
          incomeSubgroupHeadingLabel(subgroup),
          null,
          IncomeStatementRowType.GROUP_HEADER
      )
    }
    accounts.each { IncomeStatementDetail detail ->
      subgroupRows << incomeStatementRow(
          section.name(),
          "${detail.accountNumber} ${detail.accountName}".toString(),
          detail,
          IncomeStatementRowType.DETAIL
      )
    }
    subgroupRows << incomeStatementRow(
        section.name(),
        summaryLabel(summaryPrefix, incomeSubgroupSummaryLabel(subgroup)),
        subgroupTotal,
        IncomeStatementRowType.SUBTOTAL
    )
    subgroupRows
  }

  private static IncomeStatementRow incomeStatementRow(
      String section,
      String label,
      IncomeStatementDetail detail,
      IncomeStatementRowType rowType
  ) {
    new IncomeStatementRow(
        section,
        label,
        detail == null ? null : scale(detail.periodAmount),
        null,
        detail == null ? null : scale(detail.yearToDateAmount),
        null,
        detail == null ? null : detail.previousYearToDateAmount == null ? null : scale(detail.previousYearToDateAmount),
        null,
        rowType
    )
  }

  private static BigDecimal incomeAmount(Totals totals, String normalBalanceSide, String incomeAccountClass) {
    BigDecimal amount = signedAmount(totals.debitAmount, totals.creditAmount, normalBalanceSide)
    incomeAccountClass == 'EXPENSE' ? scale(amount.negate()) : scale(amount)
  }

  private static IncomeStatementDetail sumIncomeStatementDetails(
      List<IncomeStatementDetail> details,
      String accountNumber,
      String accountName
  ) {
    boolean hasPrevious = details.any { IncomeStatementDetail detail -> detail.previousYearToDateAmount != null }
    new IncomeStatementDetail(
        accountNumber,
        accountName,
        scale(details.sum(BigDecimal.ZERO) { IncomeStatementDetail detail -> detail.periodAmount } as BigDecimal),
        scale(details.sum(BigDecimal.ZERO) { IncomeStatementDetail detail -> detail.yearToDateAmount } as BigDecimal),
        hasPrevious
            ? scale(details.sum(BigDecimal.ZERO) { IncomeStatementDetail detail -> detail.previousYearToDateAmount ?: BigDecimal.ZERO } as BigDecimal)
            : null
    )
  }

  private static IncomeStatementDetail zeroIncomeStatementDetail() {
    new IncomeStatementDetail(null, null, BigDecimal.ZERO, BigDecimal.ZERO, null)
  }

  private static boolean hasIncomeAmount(IncomeStatementDetail detail) {
    detail.periodAmount != BigDecimal.ZERO ||
        detail.yearToDateAmount != BigDecimal.ZERO ||
        (detail.previousYearToDateAmount != null && detail.previousYearToDateAmount != BigDecimal.ZERO)
  }

  private static void enrichIncomeStatementPercentages(
      List<IncomeStatementRow> rows,
      BigDecimal periodRevenue,
      BigDecimal yearToDateRevenue
  ) {
    rows.each { IncomeStatementRow row ->
      row.periodRevenueShare = percentOf(row.periodAmount, periodRevenue)
      row.yearToDateRevenueShare = percentOf(row.yearToDateAmount, yearToDateRevenue)
      row.previousComparison = comparisonPercent(row.yearToDateAmount, row.previousYearToDateAmount)
    }
  }

  private static BigDecimal percentOf(BigDecimal amount, BigDecimal base) {
    if (amount == null || base == null || base == BigDecimal.ZERO) {
      return null
    }
    scale(amount * 100G / base)
  }

  private static BigDecimal comparisonPercent(BigDecimal amount, BigDecimal previousAmount) {
    if (amount == null || previousAmount == null || previousAmount == BigDecimal.ZERO) {
      return null
    }
    scale((amount - previousAmount) * 100G / previousAmount.abs())
  }

  private static FiscalYear findPreviousFiscalYear(Sql sql, EffectiveSelection effective) {
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
         limit 1
    ''', [effective.companyId, Date.valueOf(effective.fiscalYear.startDate)]) as GroovyRowResult
    row == null ? null : mapFiscalYear(row)
  }

  private static LocalDate comparisonEndDate(EffectiveSelection effective, FiscalYear comparisonYear) {
    long daysFromCurrentYearStart = ChronoUnit.DAYS.between(effective.fiscalYear.startDate, effective.endDate)
    LocalDate endDate = comparisonYear.startDate.plusDays(daysFromCurrentYearStart)
    endDate.isAfter(comparisonYear.endDate) ? comparisonYear.endDate : endDate
  }

  private static FiscalYear mapFiscalYear(GroovyRowResult row) {
    Object closedAt = row.get('closedAt')
    new FiscalYear(
        Long.valueOf(row.get('id').toString()),
        row.get('name') as String,
        SqlValueMapper.toLocalDate(row.get('startDate')),
        SqlValueMapper.toLocalDate(row.get('endDate')),
        Boolean.valueOf(row.get('closed').toString()),
        closedAt instanceof Timestamp ? ((Timestamp) closedAt).toLocalDateTime() : closedAt as LocalDateTime
    )
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

  private static String summaryLabel(String prefix, String displayName) {
    displayName
        ? "${prefix} ${displayName.substring(0, 1).toLowerCase(I18n.instance.locale)}${displayName.substring(1)}".toString()
        : prefix
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
    section == IncomeStatementSection.NET_RESULT ? section.displayName.toUpperCase(I18n.instance.locale) : section.displayName
  }

  private static boolean shouldAddIncomeGroupHeader(IncomeStatementSection section, List<IncomeStatementDetail> accounts) {
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

  private static IncomeStatementDetail computeSectionResult(
      IncomeStatementSection section,
      Map<IncomeStatementSection, IncomeStatementDetail> sectionTotals
  ) {
    switch (section) {
      case IncomeStatementSection.OPERATING_RESULT:
        return sumIncomeStatementDetails([
            sectionTotals[IncomeStatementSection.OPERATING_INCOME],
            sectionTotals[IncomeStatementSection.OPERATING_EXPENSES]
        ].findAll(), null, section.displayName)
      case IncomeStatementSection.RESULT_AFTER_FINANCIAL:
        return sumIncomeStatementDetails([
            sectionTotals[IncomeStatementSection.OPERATING_RESULT],
            sectionTotals[IncomeStatementSection.FINANCIAL_ITEMS]
        ].findAll(), null, section.displayName)
      case IncomeStatementSection.RESULT_AFTER_EXTRAORDINARY:
        return sectionTotals[IncomeStatementSection.RESULT_AFTER_FINANCIAL] ?: zeroIncomeStatementDetail()
      case IncomeStatementSection.PROFIT_BEFORE_TAX:
        return sumIncomeStatementDetails([
            sectionTotals[IncomeStatementSection.RESULT_AFTER_EXTRAORDINARY],
            sectionTotals[IncomeStatementSection.APPROPRIATIONS]
        ].findAll(), null, section.displayName)
      case IncomeStatementSection.NET_RESULT:
        return sumIncomeStatementDetails([
            sectionTotals[IncomeStatementSection.PROFIT_BEFORE_TAX],
            sectionTotals[IncomeStatementSection.TAX]
        ].findAll(), null, section.displayName)
      default:
        throw new IllegalStateException("Okänd beräknad sektion: ${section}")
    }
  }

  @SuppressWarnings('AbcMetric')
  private ReportResult buildBalanceSheetReport(EffectiveSelection effective) {
    databaseService.withSql { Sql sql ->
      Map<String, AccountInfo> accountInfos = ReportSqlLoader.loadAccountInfos(sql, effective.companyId)
      Map<String, BigDecimal> fiscalOpeningBalances = ReportSqlLoader.loadOpeningBalances(sql, effective.selection.fiscalYearId)
      Map<String, BigDecimal> prePeriodMovements = ReportSqlLoader.loadBalanceSheetMovements(
          sql,
          effective.selection.fiscalYearId,
          effective.fiscalYear.startDate,
          effective.startDate.minusDays(1)
      )
      Map<String, BigDecimal> openingBalances = buildClosingBalances(accountInfos, fiscalOpeningBalances, prePeriodMovements)
      Map<String, BigDecimal> periodMovements = ReportSqlLoader.loadBalanceSheetMovements(
          sql,
          effective.selection.fiscalYearId,
          effective.startDate,
          effective.endDate
      )

      Map<String, BigDecimal> closingBalances = buildClosingBalances(accountInfos, openingBalances, periodMovements)
      List<String> skippedAccounts = []
      Map<AccountSubgroup, List<BalanceSheetDetail>> subgroupAccounts = buildBalanceSheetSubgroupAccounts(
          accountInfos,
          openingBalances,
          periodMovements,
          closingBalances,
          skippedAccounts
      )
      Map<AccountSubgroup, BalanceSheetDetail> subgroupTotals = subgroupAccounts.collectEntries { AccountSubgroup sg, List<BalanceSheetDetail> details ->
        [(sg): sumBalanceSheetDetails(details, null, null)]
      } as Map<AccountSubgroup, BalanceSheetDetail>

      BalanceSheetBuildResult buildResult = buildBalanceSheetRows(subgroupAccounts, subgroupTotals)
      List<BalanceSheetRow> rows = buildResult.rows
      Map<BalanceSheetSection, BalanceSheetDetail> sectionTotals = buildResult.sectionTotals

      BigDecimal assetTotal = sectionTotals[BalanceSheetSection.TOTAL_ASSETS]?.closingBalance ?: BigDecimal.ZERO
      BigDecimal equityAndLiabilitiesTotal = sectionTotals[BalanceSheetSection.TOTAL_EQUITY_AND_LIABILITIES]?.closingBalance ?: BigDecimal.ZERO

      List<String> summaryLines = [
          "${BalanceSheetSection.TOTAL_ASSETS.displayName}: ${formatAmountLocale(scale(assetTotal), effective.locale)}".toString(),
          "${BalanceSheetSection.TOTAL_EQUITY_AND_LIABILITIES.displayName}: ${formatAmountLocale(scale(equityAndLiabilitiesTotal), effective.locale)}".toString()
      ]
      if (skippedAccounts) {
        summaryLines.add(I18n.instance.format('balanceSheetReport.summary.unmappedAccounts', skippedAccounts.join(', ')))
      }

      createResult(
          effective,
          summaryLines,
          [
              I18n.instance.getString('balanceSheetSection.column.item'),
              I18n.instance.getString('balanceSheetSection.column.opening'),
              I18n.instance.getString('balanceSheetSection.column.period'),
              I18n.instance.getString('balanceSheetSection.column.closing')
          ],
          rows.collect { BalanceSheetRow row ->
            String label = row.accountNumber
                ? "${row.accountNumber} ${row.accountName}"
                : (row.subgroupDisplayName ?: row.section)
            stringRow(
                label,
                formatAmountLocale(row.openingBalance, effective.locale),
                formatAmountLocale(row.periodMovement, effective.locale),
                formatAmountLocale(row.closingBalance, effective.locale)
            )
          },
          rows.collect { BalanceSheetRow ignored -> null as Long } as List<Long>,
          [typedRows: rows, assetTotal: scale(assetTotal), equityAndLiabilitiesTotal: scale(equityAndLiabilitiesTotal)]
      )
    } as ReportResult
  }

  private BalanceSheetBuildResult buildBalanceSheetRows(
      Map<AccountSubgroup, List<BalanceSheetDetail>> subgroupAccounts,
      Map<AccountSubgroup, BalanceSheetDetail> subgroupTotals
  ) {
    List<BalanceSheetRow> rows = []
    Map<BalanceSheetSection, BalanceSheetDetail> sectionTotals = [:]

    BalanceSheetSection.values().each { BalanceSheetSection section ->
      if (section.computed) {
        BalanceSheetDetail computedTotal = computeBalanceSheetTotal(section, sectionTotals)
        sectionTotals[section] = computedTotal
        rows << balanceSheetSummaryRow(section, computedTotal, section.displayName, BalanceSheetRowType.GRAND_TOTAL)
        return
      }
      List<BalanceSheetDetail> sectionDetails = []
      section.subgroups.each { AccountSubgroup subgroup ->
        List<BalanceSheetDetail> accounts = subgroupAccounts[subgroup] ?: []
        BalanceSheetDetail subgroupTotal = subgroupTotals[subgroup] ?: zeroBalanceSheetDetail(null, subgroup.displayName)
        rows.addAll(accounts.collect { BalanceSheetDetail detail -> balanceSheetDetailRow(section, detail) })
        if (accounts.size() > 0 && hasBalanceSheetAmount(subgroupTotal)) {
          rows << balanceSheetSummaryRow(section, subgroupTotal, subgroup.displayName, BalanceSheetRowType.SUBGROUP_TOTAL)
        }
        sectionDetails.addAll(accounts)
      }
      BalanceSheetDetail sectionTotal = sumBalanceSheetDetails(sectionDetails, null, section.displayName)
      sectionTotals[section] = sectionTotal
      if (hasBalanceSheetAmount(sectionTotal)) {
        rows << balanceSheetSummaryRow(section, sectionTotal, section.displayName, BalanceSheetRowType.SECTION_TOTAL)
      }
    }
    new BalanceSheetBuildResult(rows, sectionTotals)
  }

  private static BalanceSheetRow balanceSheetDetailRow(BalanceSheetSection section, BalanceSheetDetail detail) {
    new BalanceSheetRow(
        section.name(),
        detail.accountNumber,
        detail.accountName,
        scale(detail.openingBalance),
        scale(detail.periodMovement),
        scale(detail.closingBalance),
        null,
        false,
        BalanceSheetRowType.DETAIL
    )
  }

  private static BalanceSheetRow balanceSheetSummaryRow(
      BalanceSheetSection section,
      BalanceSheetDetail detail,
      String label,
      BalanceSheetRowType rowType
  ) {
    new BalanceSheetRow(
        section.name(),
        null,
        null,
        scale(detail.openingBalance),
        scale(detail.periodMovement),
        scale(detail.closingBalance),
        label,
        true,
        rowType
    )
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

  private Map<AccountSubgroup, List<BalanceSheetDetail>> buildBalanceSheetSubgroupAccounts(
      Map<String, AccountInfo> accountInfos,
      Map<String, BigDecimal> openingBalances,
      Map<String, BigDecimal> periodMovements,
      Map<String, BigDecimal> closingBalances,
      List<String> skippedAccounts
  ) {
    Map<AccountSubgroup, List<BalanceSheetDetail>> subgroupAccounts = [:]
    Set<String> accountNumbers = ([] as Set<String>)
    accountNumbers.addAll(openingBalances.keySet())
    accountNumbers.addAll(periodMovements.keySet())
    accountNumbers.addAll(closingBalances.keySet())
    accountNumbers.sort().each { String accountNumber ->
      AccountInfo info = accountInfos[accountNumber]
      if (info == null || !(info.accountClass in ['ASSET', 'LIABILITY', 'EQUITY'])) {
        return
      }
      AccountSubgroup subgroup = info.accountSubgroup
          ? AccountSubgroup.fromDatabaseValue(info.accountSubgroup)
          : AccountSubgroup.fromAccountNumber(accountNumber)
      if (subgroup == null) {
        log.warning("Konto ${accountNumber} (${info.accountName}) saknar undergrupp och exkluderas från balansrapporten.")
        skippedAccounts.add("${accountNumber} ${info.accountName}".toString())
        return
      }
      subgroup = resolveBalanceSheetSubgroup(subgroup, info.accountClass)
      BalanceSheetDetail detail = new BalanceSheetDetail(
          accountNumber,
          info.accountName,
          scale(openingBalances[accountNumber] ?: BigDecimal.ZERO),
          scale(periodMovements[accountNumber] ?: BigDecimal.ZERO),
          scale(closingBalances[accountNumber] ?: BigDecimal.ZERO)
      )
      if (detail.openingBalance == BigDecimal.ZERO && detail.periodMovement == BigDecimal.ZERO && detail.closingBalance == BigDecimal.ZERO) {
        return
      }
      List<BalanceSheetDetail> list = subgroupAccounts.computeIfAbsent(subgroup) { [] }
      list.add(detail)
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

  private static BalanceSheetDetail computeBalanceSheetTotal(
      BalanceSheetSection section,
      Map<BalanceSheetSection, BalanceSheetDetail> sectionTotals
  ) {
    switch (section) {
      case BalanceSheetSection.TOTAL_ASSETS:
        return sumBalanceSheetDetails([
            sectionTotals[BalanceSheetSection.FIXED_ASSETS],
            sectionTotals[BalanceSheetSection.CURRENT_ASSETS]
        ].findAll(), null, section.displayName)
      case BalanceSheetSection.TOTAL_EQUITY_AND_LIABILITIES:
        return sumBalanceSheetDetails([
            sectionTotals[BalanceSheetSection.EQUITY],
            sectionTotals[BalanceSheetSection.UNTAXED_RESERVES],
            sectionTotals[BalanceSheetSection.PROVISIONS],
            sectionTotals[BalanceSheetSection.LONG_TERM_LIABILITIES],
            sectionTotals[BalanceSheetSection.CURRENT_LIABILITIES]
        ].findAll(), null, section.displayName)
      default:
        throw new IllegalStateException("Okänd beräknad sektion: ${section}")
    }
  }

  private static BalanceSheetDetail sumBalanceSheetDetails(
      List<BalanceSheetDetail> details,
      String accountNumber,
      String accountName
  ) {
    new BalanceSheetDetail(
        accountNumber,
        accountName,
        scale(details.sum(BigDecimal.ZERO) { BalanceSheetDetail detail -> detail.openingBalance } as BigDecimal),
        scale(details.sum(BigDecimal.ZERO) { BalanceSheetDetail detail -> detail.periodMovement } as BigDecimal),
        scale(details.sum(BigDecimal.ZERO) { BalanceSheetDetail detail -> detail.closingBalance } as BigDecimal)
    )
  }

  private static BalanceSheetDetail zeroBalanceSheetDetail(String accountNumber, String accountName) {
    new BalanceSheetDetail(accountNumber, accountName, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
  }

  private static boolean hasBalanceSheetAmount(BalanceSheetDetail detail) {
    detail.openingBalance != BigDecimal.ZERO ||
        detail.periodMovement != BigDecimal.ZERO ||
        detail.closingBalance != BigDecimal.ZERO
  }

  private ReportResult buildTransactionReport(EffectiveSelection effective) {
    List<TransactionReportRow> rows = databaseService.withSql { Sql sql ->
      ReportSqlLoader.loadPostingLines(sql, effective.selection.fiscalYearId, effective.startDate, effective.endDate)
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
            I18n.instance.format('transactionReport.summary.debitTotal', formatAmountLocale(scale(debitTotal), effective.locale)),
            I18n.instance.format('transactionReport.summary.creditTotal', formatAmountLocale(scale(creditTotal), effective.locale))
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
              formatAmountLocale(row.debitAmount, effective.locale),
              formatAmountLocale(row.creditAmount, effective.locale),
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
              I18n.instance.format('vatReport.summary.outputVat', formatAmountLocale(scale(outputTotal), effective.locale)),
              I18n.instance.format('vatReport.summary.inputVat', formatAmountLocale(scale(inputTotal), effective.locale)),
              I18n.instance.format('vatReport.summary.net', formatAmountLocale(netTotal, effective.locale))
          ],
          [
              I18n.instance.getString('vatReport.column.code'),
              I18n.instance.getString('vatReport.column.label'),
              I18n.instance.getString('vatReport.column.base'),
              I18n.instance.getString('vatReport.column.outputVat'),
              I18n.instance.getString('vatReport.column.inputVat')
          ],
          rows.collect { VatReportEntry row ->
            stringRow(row.vatCode, row.label, formatAmountLocale(row.baseAmount, effective.locale), formatAmountLocale(row.outputVatAmount, effective.locale), formatAmountLocale(row.inputVatAmount, effective.locale))
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
    Locale locale = resolveCompanyLocale(companyId)
    String selectionLabel = period == null
        ? I18n.instance.format('report.selection.interval', startDate.toString(), endDate.toString())
        : I18n.instance.format('report.selection.period', period.periodName, startDate.toString(), endDate.toString())
    new EffectiveSelection(selection, fiscalYear, companyId, startDate, endDate, selectionLabel, locale)
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
        orgNumberLabel: I18n.instance.getString('report.common.orgNumber'),
        pageLabel     : I18n.instance.getString('report.common.page')
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

  private Locale resolveCompanyLocale(long companyId) {
    String localeTag = databaseService.withSql { Sql sql ->
      sql.firstRow('select locale_tag from company where id = ?', [companyId])?.locale_tag as String
    }
    AmountFormatter.resolveLocale(localeTag)
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


  private static String formatAmountLocale(BigDecimal amount, Locale locale) {
    AmountFormatter.format(amount, locale)
  }

  private static String formatNullableAmount(BigDecimal amount, Locale locale) {
    amount == null ? '' : formatAmountLocale(amount, locale)
  }

  private static List<String> stringRow(String... values) {
    values.toList()
  }
}
