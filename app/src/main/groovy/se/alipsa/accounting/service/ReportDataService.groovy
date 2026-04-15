package se.alipsa.accounting.service

import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.transform.Canonical

import se.alipsa.accounting.domain.AccountingPeriod
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.VatCode
import se.alipsa.accounting.domain.report.BalanceSheetRow
import se.alipsa.accounting.domain.report.GeneralLedgerRow
import se.alipsa.accounting.domain.report.IncomeStatementRow
import se.alipsa.accounting.domain.report.ReportResult
import se.alipsa.accounting.domain.report.ReportSelection
import se.alipsa.accounting.domain.report.ReportType
import se.alipsa.accounting.domain.report.TransactionReportRow
import se.alipsa.accounting.domain.report.TrialBalanceRow
import se.alipsa.accounting.domain.report.VatReportEntry
import se.alipsa.accounting.domain.report.VoucherListRow

import java.math.RoundingMode
import java.sql.Date
import java.time.LocalDate

/**
 * Builds reusable report data for UI previews, CSV export and Journo PDF rendering.
 */
final class ReportDataService {

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
    List<VoucherListRow> rows = databaseService.withSql { Sql sql ->
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
    List<String> headers = ['Datum', 'Verifikation', 'Serie', 'Text', 'Status', 'Debet', 'Kredit']
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
            "Antal verifikationer: ${rows.size()}".toString(),
            "Debet totalt: ${formatAmount(scale(debitTotal))}".toString(),
            "Kredit totalt: ${formatAmount(scale(creditTotal))}".toString()
        ],
        headers,
        tableRows,
        rows.collect { VoucherListRow row -> row.voucherId },
        [typedRows: rows]
    )
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
            'Ingående balans',
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
      List<String> headers = ['Konto', 'Namn', 'Datum', 'Verifikation', 'Text', 'Debet', 'Kredit', 'Saldo']
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
          ["Antal rader: ${rows.size()}".toString()],
          headers,
          tableRows,
          rows.collect { GeneralLedgerRow row -> row.voucherId },
          [typedRows: rows]
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
        BigDecimal opening = scale((openingBalances[accountNumber] ?: BigDecimal.ZERO) + (priorBalances[accountNumber] ?: BigDecimal.ZERO))
        BigDecimal closing = scale(opening + signedAmount(totals.debitAmount, totals.creditAmount, info.normalBalanceSide))
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
              "Ingående saldo: ${formatAmount(scale(openingTotal))}".toString(),
              "Periodens debet: ${formatAmount(scale(debitTotal))}".toString(),
              "Periodens kredit: ${formatAmount(scale(creditTotal))}".toString(),
              "Utgående saldo: ${formatAmount(scale(closingTotal))}".toString()
          ],
          ['Konto', 'Namn', 'Ingående', 'Debet', 'Kredit', 'Utgående'],
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

  private ReportResult buildIncomeStatementReport(EffectiveSelection effective) {
    databaseService.withSql { Sql sql ->
      Map<String, AccountInfo> accountInfos = loadAccountInfos(sql, effective.companyId)
      Map<String, Totals> periodTotals = loadPeriodTotals(sql, effective.selection.fiscalYearId, effective.startDate, effective.endDate)
      List<IncomeStatementRow> rows = accountInfos.keySet().sort().collect { String accountNumber ->
        AccountInfo info = accountInfos[accountNumber]
        if (!(info.accountClass in ['INCOME', 'EXPENSE'])) {
          return null
        }
        Totals totals = periodTotals[accountNumber] ?: Totals.ZERO
        BigDecimal amount = signedAmount(totals.debitAmount, totals.creditAmount, info.normalBalanceSide)
        if (amount == BigDecimal.ZERO) {
          return null
        }
        new IncomeStatementRow(info.accountClass == 'INCOME' ? 'Intäkter' : 'Kostnader', accountNumber, info.accountName, scale(amount))
      }.findAll { IncomeStatementRow row -> row != null }
      BigDecimal incomeTotal = rows.findAll { IncomeStatementRow row -> row.section == 'Intäkter' }
          .sum(BigDecimal.ZERO) { IncomeStatementRow row -> row.amount } as BigDecimal
      BigDecimal expenseTotal = rows.findAll { IncomeStatementRow row -> row.section == 'Kostnader' }
          .sum(BigDecimal.ZERO) { IncomeStatementRow row -> row.amount } as BigDecimal
      BigDecimal result = scale(incomeTotal - expenseTotal)
      createResult(
          effective,
          [
              "Intäkter: ${formatAmount(scale(incomeTotal))}".toString(),
              "Kostnader: ${formatAmount(scale(expenseTotal))}".toString(),
              "Resultat: ${formatAmount(result)}".toString()
          ],
          ['Sektion', 'Konto', 'Namn', 'Belopp'],
          rows.collect { IncomeStatementRow row ->
            stringRow(row.section, row.accountNumber, row.accountName, formatAmount(row.amount))
          },
          rows.collect { IncomeStatementRow ignored -> null as Long } as List<Long>,
          [typedRows: rows, incomeTotal: scale(incomeTotal), expenseTotal: scale(expenseTotal), result: result]
      )
    } as ReportResult
  }

  private ReportResult buildBalanceSheetReport(EffectiveSelection effective) {
    databaseService.withSql { Sql sql ->
      Map<String, AccountInfo> accountInfos = loadAccountInfos(sql, effective.companyId)
      Map<String, BigDecimal> openingBalances = loadOpeningBalances(sql, effective.selection.fiscalYearId)
      Map<String, BigDecimal> movements = loadSignedMovements(sql, effective.selection.fiscalYearId, effective.fiscalYear.startDate, effective.endDate)
      List<BalanceSheetRow> rows = accountInfos.keySet().sort().collect { String accountNumber ->
        AccountInfo info = accountInfos[accountNumber]
        if (!(info.accountClass in ['ASSET', 'LIABILITY', 'EQUITY'])) {
          return null
        }
        BigDecimal amount = scale((openingBalances[accountNumber] ?: BigDecimal.ZERO) + (movements[accountNumber] ?: BigDecimal.ZERO))
        if (amount == BigDecimal.ZERO) {
          return null
        }
        String section
        switch (info.accountClass) {
          case 'ASSET':
            section = 'Tillgångar'
            break
          case 'LIABILITY':
            section = 'Skulder'
            break
          default:
            section = 'Eget kapital'
        }
        new BalanceSheetRow(section, accountNumber, info.accountName, amount)
      }.findAll { BalanceSheetRow row -> row != null }
      BigDecimal assetTotal = rows.findAll { BalanceSheetRow row -> row.section == 'Tillgångar' }
          .sum(BigDecimal.ZERO) { BalanceSheetRow row -> row.amount } as BigDecimal
      BigDecimal liabilityTotal = rows.findAll { BalanceSheetRow row -> row.section == 'Skulder' }
          .sum(BigDecimal.ZERO) { BalanceSheetRow row -> row.amount } as BigDecimal
      BigDecimal equityTotal = rows.findAll { BalanceSheetRow row -> row.section == 'Eget kapital' }
          .sum(BigDecimal.ZERO) { BalanceSheetRow row -> row.amount } as BigDecimal
      createResult(
          effective,
          [
              "Tillgångar: ${formatAmount(scale(assetTotal))}".toString(),
              "Skulder: ${formatAmount(scale(liabilityTotal))}".toString(),
              "Eget kapital: ${formatAmount(scale(equityTotal))}".toString(),
              "Skulder + eget kapital: ${formatAmount(scale(liabilityTotal + equityTotal))}".toString()
          ],
          ['Sektion', 'Konto', 'Namn', 'Belopp'],
          rows.collect { BalanceSheetRow row ->
            stringRow(row.section, row.accountNumber, row.accountName, formatAmount(row.amount))
          },
          rows.collect { BalanceSheetRow ignored -> null as Long } as List<Long>,
          [typedRows: rows, assetTotal: scale(assetTotal), liabilityTotal: scale(liabilityTotal), equityTotal: scale(equityTotal)]
      )
    } as ReportResult
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
            "Antal transaktioner: ${rows.size()}".toString(),
            "Debet totalt: ${formatAmount(scale(debitTotal))}".toString(),
            "Kredit totalt: ${formatAmount(scale(creditTotal))}".toString()
        ],
        ['Datum', 'Verifikation', 'Konto', 'Kontonamn', 'Verifikationstext', 'Radtext', 'Debet', 'Kredit', 'Status'],
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
        [typedRows: rows]
    )
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
              "Utgående moms: ${formatAmount(scale(outputTotal))}".toString(),
              "Ingående moms: ${formatAmount(scale(inputTotal))}".toString(),
              "Netto: ${formatAmount(netTotal)}".toString()
          ],
          ['Momskod', 'Benämning', 'Bas', 'Utgående moms', 'Ingående moms'],
          rows.collect { VatReportEntry row ->
            stringRow(row.vatCode, row.label, formatAmount(row.baseAmount), formatAmount(row.outputVatAmount), formatAmount(row.inputVatAmount))
          },
          rows.collect { VatReportEntry ignored -> null as Long } as List<Long>,
          [typedRows: rows, outputTotal: scale(outputTotal), inputTotal: scale(inputTotal), netTotal: netTotal]
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
        ? "Intervall ${startDate} - ${endDate}"
        : "Period ${period.periodName} (${startDate} - ${endDate})"
    new EffectiveSelection(selection, fiscalYear, companyId, startDate, endDate, selectionLabel)
  }

  private static Map<String, AccountInfo> loadAccountInfos(Sql sql, long companyId) {
    Map<String, AccountInfo> accounts = [:]
    sql.rows('''
        select id as accountId,
               account_number as accountNumber,
               account_name as accountName,
               account_class as accountClass,
               normal_balance_side as normalBalanceSide
          from account
         where company_id = ?
         order by account_number
    ''', [companyId]).each { GroovyRowResult row ->
      accounts.put(row.get('accountNumber') as String, new AccountInfo(
          ((Number) row.get('accountId')).longValue(),
          row.get('accountName') as String,
          row.get('accountClass') as String,
          row.get('normalBalanceSide') as String
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
        tableRows     : tableRows
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
  }

  @Canonical
  private static final class Totals {

    static final Totals ZERO = new Totals(BigDecimal.ZERO.setScale(AMOUNT_SCALE), BigDecimal.ZERO.setScale(AMOUNT_SCALE))

    BigDecimal debitAmount
    BigDecimal creditAmount
  }

  private static final class VatBucket {

    BigDecimal baseAmount = BigDecimal.ZERO.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)
    BigDecimal postedOutputVat = BigDecimal.ZERO.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)
    BigDecimal postedInputVat = BigDecimal.ZERO.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)
    int outputPostingCount
    int inputPostingCount
  }
}
