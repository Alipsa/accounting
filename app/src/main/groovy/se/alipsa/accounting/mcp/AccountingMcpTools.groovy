package se.alipsa.accounting.mcp

import groovy.json.JsonOutput

import se.alipsa.accounting.domain.Account
import se.alipsa.accounting.domain.Company
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.VatPeriod
import se.alipsa.accounting.domain.Voucher
import se.alipsa.accounting.domain.VoucherLine
import se.alipsa.accounting.domain.report.GeneralLedgerRow
import se.alipsa.accounting.domain.report.ReportSelection
import se.alipsa.accounting.domain.report.ReportType
import se.alipsa.accounting.domain.report.TrialBalanceRow
import se.alipsa.accounting.service.AccountService
import se.alipsa.accounting.service.ClosingService
import se.alipsa.accounting.service.CompanyService
import se.alipsa.accounting.service.FiscalYearService
import se.alipsa.accounting.service.ReportDataService
import se.alipsa.accounting.service.VatService
import se.alipsa.accounting.service.VoucherService
import se.alipsa.accounting.service.YearEndClosingPreview
import se.alipsa.accounting.service.YearEndClosingResult

import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Registry and implementation entrypoint for MCP tools.
 *
 * Phase 2 exposes read-only tools that prove service wiring and structured MCP output.
 */
class AccountingMcpTools {

  private final CompanyService companyService
  private final FiscalYearService fiscalYearService
  private final AccountService accountService
  private final VoucherService voucherService
  private final VatService vatService
  private final ClosingService closingService
  private final ReportDataService reportDataService

  AccountingMcpTools() {
    this(
        new CompanyService(),
        new FiscalYearService(),
        new AccountService(),
        new VoucherService(),
        new VatService(),
        new ClosingService(),
        new ReportDataService()
    )
  }

  AccountingMcpTools(
      CompanyService companyService,
      FiscalYearService fiscalYearService,
      AccountService accountService,
      VoucherService voucherService,
      VatService vatService,
      ClosingService closingService,
      ReportDataService reportDataService
  ) {
    this.companyService = companyService
    this.fiscalYearService = fiscalYearService
    this.accountService = accountService
    this.voucherService = voucherService
    this.vatService = vatService
    this.closingService = closingService
    this.reportDataService = reportDataService
  }

  List<Map<String, Object>> listTools() {
    readOnlyToolDefs() + voucherToolDefs() + vatWriteToolDefs() + yearEndToolDefs()
  }

  private static List<Map<String, Object>> readOnlyToolDefs() {
    [
        toolDef('get_company_info',
            'Returns the company record for the given company ID.',
            ['company_id'],
            [company_id: intParam('Company ID')]
        ),
        toolDef('list_fiscal_years',
            'Lists all fiscal years for the given company.',
            ['company_id'],
            [company_id: intParam('Company ID')]
        ),
        toolDef('list_accounts',
            'Returns active accounts in the chart of accounts for the given company. Accepts an optional query string.',
            ['company_id'],
            [
                company_id: intParam('Company ID'),
                query: optStrParam('Optional search string (account number or name)')
            ]
        ),
        toolDef('list_vouchers',
            'Returns posted vouchers for the given fiscal year. Returns at most 200 rows.',
            ['company_id', 'fiscal_year_id'],
            [
                company_id: intParam('Company ID'),
                fiscal_year_id: intParam('Fiscal year ID')
            ]
        ),
        toolDef('get_trial_balance',
            'Returns trial balance (råbalans) for the given fiscal year with opening balance, period movements and closing balance per account.',
            ['company_id', 'fiscal_year_id'],
            [
                company_id: intParam('Company ID'),
                fiscal_year_id: intParam('Fiscal year ID'),
                accounting_period_id: optIntParam('Optional: restrict to a specific accounting period.'),
                start_date: optStrParam('Optional: restrict start date (ISO YYYY-MM-DD).'),
                end_date: optStrParam('Optional: restrict end date (ISO YYYY-MM-DD).')
            ]
        ),
        toolDef('get_general_ledger',
            'Returns the general ledger (huvudbok). One row per posting with running balance. Use limit to manage large years.',
            ['company_id', 'fiscal_year_id'],
            [
                company_id: intParam('Company ID'),
                fiscal_year_id: intParam('Fiscal year ID'),
                accounting_period_id: optIntParam('Optional: restrict to a specific accounting period.'),
                start_date: optStrParam('Optional: restrict start date (ISO YYYY-MM-DD).'),
                end_date: optStrParam('Optional: restrict end date (ISO YYYY-MM-DD).'),
                limit: optIntParam('Max rows returned. Default 1000, max 5000.')
            ]
        ),
        toolDef('list_vat_periods',
            'Lists VAT periods for the given fiscal year with status (OPEN, REPORTED, LOCKED).',
            ['company_id', 'fiscal_year_id'],
            [
                company_id: intParam('Company ID'),
                fiscal_year_id: intParam('Fiscal year ID')
            ]
        ),
        toolDef('get_vat_report',
            'Calculates the VAT report for the given VAT period. Returns output VAT, input VAT, net payable, and per-code breakdown.',
            ['company_id', 'vat_period_id'],
            [
                company_id: intParam('Company ID'),
                vat_period_id: intParam('VAT period ID')
            ]
        ),
    ]
  }

  private static List<Map<String, Object>> voucherToolDefs() {
    [
        toolDef('preview_voucher',
            'Validates a voucher proposal without posting it. Returns resolved accounts, balance check, and any errors or warnings. VAT reporting uses VAT codes configured on accounts; per-line vat_code is not accepted in Phase 3.',
            ['company_id', 'fiscal_year_id', 'series_code', 'accounting_date', 'description', 'lines'],
            [
                company_id: intParam('Company ID'),
                fiscal_year_id: intParam('Fiscal year ID'),
                series_code: strParam('Voucher series code, e.g. "A"'),
                accounting_date: strParam('Accounting date in ISO format YYYY-MM-DD'),
                description: strParam('Voucher description'),
                lines: voucherLinesParam()
            ]
        ),
        toolDef('post_voucher',
            'Posts a voucher. Use preview_voucher first to validate. The lines must be balanced (debit total = credit total). VAT reporting uses VAT codes configured on accounts; per-line vat_code is not accepted in Phase 3.',
            ['company_id', 'fiscal_year_id', 'series_code', 'accounting_date', 'description', 'lines', 'preview_token'],
            [
                company_id: intParam('Company ID'),
                fiscal_year_id: intParam('Fiscal year ID'),
                series_code: strParam('Voucher series code, e.g. "A"'),
                accounting_date: strParam('Accounting date in ISO format YYYY-MM-DD'),
                description: strParam('Voucher description'),
                lines: voucherLinesParam(),
                preview_token: strParam('Token returned by preview_voucher for the exact same payload.')
            ]
        ),
        toolDef('create_correction_voucher',
            'Creates a reversing correction voucher for an existing posted voucher. Direct edits to posted vouchers are not permitted.',
            ['original_voucher_id'],
            [
                original_voucher_id: intParam('ID of the voucher to correct'),
                description: optStrParam('Optional description for the correction. Defaults to "Korrigering av <original>".')
            ]
        ),
    ]
  }

  private static List<Map<String, Object>> vatWriteToolDefs() {
    [
        toolDef('book_vat_transfer',
            'Books the VAT transfer voucher for a VAT period. Run get_vat_report first and pass back its report_hash.',
            ['company_id', 'vat_period_id', 'report_hash'],
            [
                company_id: intParam('Company ID'),
                vat_period_id: intParam('VAT period ID'),
                report_hash: strParam('Hash returned by get_vat_report for the current VAT report.'),
                series_code: optStrParam('Optional voucher series code. Defaults to "M".'),
                settlement_account: optStrParam('Optional settlement account number. Defaults to "2650".')
            ]
        ),
    ]
  }

  private static List<Map<String, Object>> yearEndToolDefs() {
    [
        toolDef('preview_year_end',
            'Runs year-end closing pre-checks. Returns blocking issues, warnings, totals, net result, and a preview_token.',
            ['company_id', 'fiscal_year_id'],
            [
                company_id: intParam('Company ID'),
                fiscal_year_id: intParam('Fiscal year ID to preview'),
                closing_account: optStrParam('Optional closing account number. Defaults to "2099".')
            ]
        ),
        toolDef('close_fiscal_year',
            'Closes the fiscal year. Requires the preview_token returned by preview_year_end for the same fiscal year and closing account.',
            ['company_id', 'fiscal_year_id', 'preview_token'],
            [
                company_id: intParam('Company ID'),
                fiscal_year_id: intParam('Fiscal year ID to close'),
                closing_account: optStrParam('Optional closing account number. Defaults to "2099".'),
                preview_token: strParam('Token returned by preview_year_end.')
            ]
        ),
    ]
  }

  Map<String, Object> callTool(String name, Map<String, Object> arguments) {
    switch (name) {
      case 'get_company_info':
        return getCompanyInfo(arguments)
      case 'list_fiscal_years':
        return listFiscalYears(arguments)
      case 'list_accounts':
        return listAccounts(arguments)
      case 'list_vouchers':
        return listVouchers(arguments)
      case 'get_trial_balance':
        return getTrialBalance(arguments)
      case 'get_general_ledger':
        return getGeneralLedger(arguments)
      case 'list_vat_periods':
        return listVatPeriods(arguments)
      case 'get_vat_report':
        return getVatReport(arguments)
      case 'book_vat_transfer':
        return bookVatTransfer(arguments)
      case 'preview_voucher':
        return previewVoucher(arguments)
      case 'post_voucher':
        return postVoucher(arguments)
      case 'create_correction_voucher':
        return createCorrectionVoucher(arguments)
      case 'preview_year_end':
        return previewYearEnd(arguments)
      case 'close_fiscal_year':
        return closeFiscalYear(arguments)
      default:
        throw new IllegalArgumentException("Unknown MCP tool: ${name}")
    }
  }

  // ---- read-only tools ----

  private Map<String, Object> getCompanyInfo(Map<String, Object> args) {
    long companyId = requiredLong(args, 'company_id')
    Company company = companyService.findById(companyId)
    if (company == null) {
      return [ok: false, error: "Company ${companyId} not found."]
    }
    [
        ok: true,
        company: [
            id: company.id,
            name: company.companyName,
            organization_number: company.organizationNumber,
            default_currency: company.defaultCurrency,
            vat_periodicity: company.vatPeriodicity?.name(),
            active: company.active
        ]
    ]
  }

  private Map<String, Object> listFiscalYears(Map<String, Object> args) {
    long companyId = requiredLong(args, 'company_id')
    List<FiscalYear> years = fiscalYearService.listFiscalYears(companyId)
    [
        ok: true,
        fiscal_years: years.collect { FiscalYear y ->
          [
              id: y.id,
              name: y.name,
              start_date: y.startDate?.toString(),
              end_date: y.endDate?.toString(),
              closed: y.closed
          ]
        }
    ]
  }

  private Map<String, Object> listAccounts(Map<String, Object> args) {
    long companyId = requiredLong(args, 'company_id')
    String query = args.get('query') as String
    List<Account> accounts =
        accountService.searchAccounts(companyId, query, null, true, false)
    [
        ok: true,
        accounts: accounts.collect { Account a ->
          [
              id: a.id,
              account_number: a.accountNumber,
              account_name: a.accountName,
              account_class: a.accountClass,
              normal_balance_side: a.normalBalanceSide,
              vat_code: a.vatCode,
              active: a.active
          ]
        }
    ]
  }

  private Map<String, Object> listVouchers(Map<String, Object> args) {
    long companyId = requiredLong(args, 'company_id')
    long fiscalYearId = requiredLong(args, 'fiscal_year_id')
    long expectedCompanyId = companyService.resolveFromFiscalYear(fiscalYearId)
    if (expectedCompanyId != companyId) {
      return [ok: false, error: "Fiscal year ${fiscalYearId} does not belong to company ${companyId}."]
    }
    List<Voucher> vouchers =
        voucherService.listVouchers(companyId, fiscalYearId, null, null)
    [
        ok: true,
        vouchers: vouchers.take(200).collect { Voucher v ->
          [
              id: v.id,
              voucher_number: v.voucherNumber,
              series_code: v.seriesCode,
              accounting_date: v.accountingDate?.toString(),
              description: v.description,
              status: v.status?.name(),
              line_count: v.lines?.size() ?: 0
          ]
        }
    ]
  }

  private Map<String, Object> getTrialBalance(Map<String, Object> args) {
    long companyId = requiredLong(args, 'company_id')
    long fiscalYearId = requiredLong(args, 'fiscal_year_id')
    Long periodId = args.get('accounting_period_id') != null
        ? ((Number) args.get('accounting_period_id')).longValue()
        : null
    LocalDate startDate = args.get('start_date') ? LocalDate.parse((String) args.get('start_date')) : null
    LocalDate endDate = args.get('end_date') ? LocalDate.parse((String) args.get('end_date')) : null
    long expectedCompanyId = companyService.resolveFromFiscalYear(fiscalYearId)
    if (expectedCompanyId != companyId) {
      return [ok: false, error: "Fiscal year ${fiscalYearId} does not belong to company ${companyId}."]
    }
    se.alipsa.accounting.domain.report.ReportResult result = reportDataService.generate(
        new ReportSelection(ReportType.TRIAL_BALANCE, fiscalYearId, periodId, startDate, endDate)
    )
    List<TrialBalanceRow> rows = (List<TrialBalanceRow>) result.templateModel.get('typedRows')
    [
        ok: true,
        fiscal_year_id: fiscalYearId,
        rows: rows.collect { TrialBalanceRow r ->
          [
              account_number: r.accountNumber,
              account_name: r.accountName,
              opening_balance: r.openingBalance,
              debit: r.debitAmount,
              credit: r.creditAmount,
              closing_balance: r.closingBalance
          ]
        }
    ]
  }

  private Map<String, Object> getGeneralLedger(Map<String, Object> args) {
    long companyId = requiredLong(args, 'company_id')
    long fiscalYearId = requiredLong(args, 'fiscal_year_id')
    Long periodId = args.get('accounting_period_id') != null
        ? ((Number) args.get('accounting_period_id')).longValue()
        : null
    LocalDate startDate = args.get('start_date') ? LocalDate.parse((String) args.get('start_date')) : null
    LocalDate endDate = args.get('end_date') ? LocalDate.parse((String) args.get('end_date')) : null
    int limit = args.get('limit') != null
        ? Math.max(1, Math.min(((Number) args.get('limit')).intValue(), 5000))
        : 1000
    long expectedCompanyId = companyService.resolveFromFiscalYear(fiscalYearId)
    if (expectedCompanyId != companyId) {
      return [ok: false, error: "Fiscal year ${fiscalYearId} does not belong to company ${companyId}."]
    }
    se.alipsa.accounting.domain.report.ReportResult result = reportDataService.generate(
        new ReportSelection(ReportType.GENERAL_LEDGER, fiscalYearId, periodId, startDate, endDate)
    )
    List<GeneralLedgerRow> rows = (List<GeneralLedgerRow>) result.templateModel.get('typedRows')
    boolean truncated = rows.size() > limit
    [
        ok: true,
        fiscal_year_id: fiscalYearId,
        truncated: truncated,
        total_rows: rows.size(),
        rows: rows.take(limit).collect { GeneralLedgerRow r ->
          [
              account_number: r.accountNumber,
              account_name: r.accountName,
              accounting_date: r.accountingDate?.toString(),
              voucher_number: r.voucherNumber,
              description: r.description,
              debit: r.debitAmount,
              credit: r.creditAmount,
              balance: r.balance,
              voucher_id: r.voucherId
          ]
        }
    ]
  }

  private Map<String, Object> listVatPeriods(Map<String, Object> args) {
    long companyId = requiredLong(args, 'company_id')
    long fiscalYearId = requiredLong(args, 'fiscal_year_id')
    long expectedCompanyId = companyService.resolveFromFiscalYear(fiscalYearId)
    if (expectedCompanyId != companyId) {
      return [ok: false, error: "Fiscal year ${fiscalYearId} does not belong to company ${companyId}."]
    }
    List<VatPeriod> periods = vatService.listPeriods(fiscalYearId)
    [
        ok: true,
        vat_periods: periods.collect { VatPeriod p ->
          [
              id: p.id,
              period_name: p.periodName,
              start_date: p.startDate?.toString(),
              end_date: p.endDate?.toString(),
              status: p.status,
              reported: p.reported,
              locked: p.locked
          ]
        }
    ]
  }

  private Map<String, Object> getVatReport(Map<String, Object> args) {
    long companyId = requiredLong(args, 'company_id')
    long vatPeriodId = requiredLong(args, 'vat_period_id')
    try {
      VatPeriod period = vatService.findPeriod(vatPeriodId)
      long expectedCompanyId = companyService.resolveFromFiscalYear(period.fiscalYearId)
      if (expectedCompanyId != companyId) {
        return [ok: false, error: "VAT period ${vatPeriodId} does not belong to company ${companyId}."]
      }
      VatService.VatReport report = vatService.calculateReport(vatPeriodId)
      String reportHash = VatService.calculateReportHash(report)
      [
          ok: true,
          vat_period_id: report.period?.id,
          period_name: report.period?.periodName,
          status: report.period?.status,
          output_vat_total: report.outputVatTotal,
          input_vat_total: report.inputVatTotal,
          net_vat_to_pay: report.netVatToPay,
          report_hash: reportHash,
          rows: report.rows.collect { VatService.VatReportRow r ->
            [
                vat_code: r.vatCode?.name(),
                label: r.label,
                base_amount: r.baseAmount,
                output_vat: r.outputVatAmount,
                input_vat: r.inputVatAmount
            ]
          }
      ]
    } catch (Exception e) {
      [ok: false, errors: [e.message ?: e.class.simpleName]]
    }
  }

  private Map<String, Object> bookVatTransfer(Map<String, Object> args) {
    long companyId = requiredLong(args, 'company_id')
    long vatPeriodId = requiredLong(args, 'vat_period_id')
    String providedHash = args.get('report_hash') as String
    if (!providedHash) {
      return [ok: false, errors: ['report_hash krävs — kör get_vat_report först och skicka med det returnerade report_hash.']]
    }
    String seriesCode = optionalString(args, 'series_code', VatService.DEFAULT_TRANSFER_SERIES)
    String settlementAccount = optionalString(args, 'settlement_account', VatService.DEFAULT_SETTLEMENT_ACCOUNT)
    try {
      VatPeriod period = vatService.findPeriod(vatPeriodId)
      if (period == null) {
        return [ok: false, errors: ["Okänd momsperiod: ${vatPeriodId}".toString()]]
      }
      long expectedCompanyId = companyService.resolveFromFiscalYear(period.fiscalYearId)
      if (expectedCompanyId != companyId) {
        return [ok: false, error: "VAT period ${vatPeriodId} does not belong to company ${companyId}."]
      }
      VatService.VatReport report = vatService.calculateReport(vatPeriodId)
      String expectedHash = VatService.calculateReportHash(report)
      if (providedHash != expectedHash) {
        return [ok: false, errors: ['report_hash stämmer inte med aktuell momsrapport — kör get_vat_report igen.']]
      }
      if (period.status == VatService.OPEN) {
        vatService.reportPeriod(vatPeriodId)
      }
      Voucher voucher = vatService.bookTransfer(vatPeriodId, seriesCode, settlementAccount)
      [
          ok: true,
          voucher_id: voucher.id,
          voucher_number: voucher.voucherNumber,
          fiscal_year_id: voucher.fiscalYearId,
          accounting_date: voucher.accountingDate?.toString(),
          description: voucher.description,
          status: voucher.status?.name(),
          line_count: voucher.lines?.size() ?: 0
      ]
    } catch (Exception exception) {
      [ok: false, errors: [exception.message ?: exception.class.simpleName]]
    }
  }

  private Map<String, Object> previewVoucher(Map<String, Object> args) {
    long companyId = requiredLong(args, 'company_id')
    long fiscalYearId = requiredLong(args, 'fiscal_year_id')
    String seriesCode = requiredString(args, 'series_code')
    String dateText = requiredString(args, 'accounting_date')
    String description = requiredString(args, 'description')
    List<Map<String, Object>> rawLines = linesArg(args)

    List<String> errors = []
    List<String> warnings = []
    FiscalYear year = fiscalYearService.findById(fiscalYearId)
    if (year == null) {
      return [ok: false, errors: ["Räkenskapsår ${fiscalYearId} hittades inte."], warnings: warnings]
    }
    long expectedCompanyId = companyService.resolveFromFiscalYear(fiscalYearId)
    if (expectedCompanyId != companyId) {
      return [ok: false, errors: ["Fiscal year ${fiscalYearId} does not belong to company ${companyId}."], warnings: warnings]
    }
    if (year.closed) {
      errors.add("Räkenskapsåret '${year.name}' är stängt. Rättelse kräver upplåsning.".toString())
    }

    LocalDate accountingDate = parseAccountingDate(dateText, errors)
    if (accountingDate != null && (accountingDate.isBefore(year.startDate) || accountingDate.isAfter(year.endDate))) {
      errors.add("Datum ${accountingDate} är utanför räkenskapsåret (${year.startDate} - ${year.endDate}).".toString())
    }

    VoucherPreview preview = resolveVoucherLines(companyId, rawLines, errors)
    if (rawLines.size() < 2) {
      errors << 'En verifikation kräver minst två rader.'
    }
    if (preview.totalDebit != preview.totalCredit) {
      errors.add("Verifikationen är obalanserad: debet ${preview.totalDebit} != kredit ${preview.totalCredit}.".toString())
    }

    boolean valid = errors.isEmpty()
    [
        ok: valid,
        errors: errors,
        warnings: warnings,
        preview_token: valid ? voucherPreviewToken(fiscalYearId, seriesCode, accountingDate?.toString() ?: dateText, description, rawLines) : null,
        fiscal_year: [id: year.id, name: year.name, closed: year.closed],
        accounting_date: accountingDate?.toString() ?: dateText,
        series_code: seriesCode,
        description: description,
        lines: preview.lines,
        total_debit: preview.totalDebit,
        total_credit: preview.totalCredit
    ]
  }

  private Map<String, Object> postVoucher(Map<String, Object> args) {
    long fiscalYearId = requiredLong(args, 'fiscal_year_id')
    String seriesCode = requiredString(args, 'series_code')
    String dateText = requiredString(args, 'accounting_date')
    String description = requiredString(args, 'description')
    List<Map<String, Object>> rawLines = linesArg(args)
    String providedToken = args.get('preview_token') as String
    if (!providedToken) {
      return [ok: false, errors: ['preview_token krävs — kör preview_voucher med exakt samma argument först.']]
    }

    String expectedToken = voucherPreviewToken(fiscalYearId, seriesCode, dateText, description, rawLines)
    if (providedToken != expectedToken) {
      return [ok: false, errors: ['preview_token stämmer inte med aktuell nyttolast — kör preview_voucher igen med exakt samma argument.']]
    }

    Map<String, Object> preview = previewVoucher(args)
    if (!(boolean) preview.ok) {
      return [ok: false, errors: preview.errors]
    }

    LocalDate accountingDate = LocalDate.parse(dateText)
    List<VoucherLine> lines = toVoucherLines((List<Map<String, Object>>) preview.lines)
    try {
      Voucher voucher = voucherService.createVoucher(fiscalYearId, seriesCode, accountingDate, description, lines)
      [
          ok: true,
          voucher_id: voucher.id,
          voucher_number: voucher.voucherNumber,
          fiscal_year_id: voucher.fiscalYearId,
          accounting_date: voucher.accountingDate?.toString(),
          description: voucher.description,
          status: voucher.status?.name(),
          line_count: voucher.lines?.size() ?: 0
      ]
    } catch (Exception exception) {
      [ok: false, errors: [exception.message ?: exception.class.simpleName]]
    }
  }

  private Map<String, Object> createCorrectionVoucher(Map<String, Object> args) {
    long originalVoucherId = requiredLong(args, 'original_voucher_id')
    String description = args.get('description') as String
    try {
      Voucher correction = voucherService.createCorrectionVoucher(originalVoucherId, description)
      [
          ok: true,
          voucher_id: correction.id,
          voucher_number: correction.voucherNumber,
          original_voucher_id: correction.originalVoucherId,
          fiscal_year_id: correction.fiscalYearId,
          accounting_date: correction.accountingDate?.toString(),
          description: correction.description,
          status: correction.status?.name(),
          line_count: correction.lines?.size() ?: 0
      ]
    } catch (Exception exception) {
      [ok: false, errors: [exception.message ?: exception.class.simpleName]]
    }
  }

  private Map<String, Object> previewYearEnd(Map<String, Object> args) {
    long companyId = requiredLong(args, 'company_id')
    long fiscalYearId = requiredLong(args, 'fiscal_year_id')
    String closingAccount = optionalString(args, 'closing_account', ClosingService.DEFAULT_CLOSING_ACCOUNT)
    try {
      long expectedCompanyId = companyService.resolveFromFiscalYear(fiscalYearId)
      if (expectedCompanyId != companyId) {
        return [ok: false, error: "Fiscal year ${fiscalYearId} does not belong to company ${companyId}."]
      }
      YearEndClosingPreview preview = closingService.previewClosing(fiscalYearId, closingAccount)
      [
          ok: true,
          fiscal_year_id: preview.fiscalYear?.id,
          fiscal_year_name: preview.fiscalYear?.name,
          next_fiscal_year_id: preview.nextFiscalYear?.id,
          next_fiscal_year_name: preview.nextFiscalYear?.name,
          next_fiscal_year_will_be_created: preview.nextFiscalYearWillBeCreated,
          closing_account: preview.closingAccountNumber,
          result_account_count: preview.resultAccountCount,
          income_total: preview.incomeTotal,
          expense_total: preview.expenseTotal,
          net_result: preview.netResult,
          blocking_issues: preview.blockingIssues,
          warnings: preview.warnings,
          ready_to_close: preview.blockingIssues.isEmpty(),
          preview_token: yearEndPreviewToken(fiscalYearId, preview.closingAccountNumber)
      ]
    } catch (Exception exception) {
      [ok: false, errors: [exception.message ?: exception.class.simpleName]]
    }
  }

  private Map<String, Object> closeFiscalYear(Map<String, Object> args) {
    long companyId = requiredLong(args, 'company_id')
    long fiscalYearId = requiredLong(args, 'fiscal_year_id')
    String closingAccount = optionalString(args, 'closing_account', ClosingService.DEFAULT_CLOSING_ACCOUNT)
    String providedToken = args.get('preview_token') as String
    if (!providedToken) {
      return [ok: false, errors: ['preview_token krävs — kör preview_year_end först.']]
    }
    String expectedToken = yearEndPreviewToken(fiscalYearId, closingAccount)
    if (providedToken != expectedToken) {
      return [ok: false, errors: ['preview_token stämmer inte — kör preview_year_end igen.']]
    }
    try {
      long expectedCompanyId = companyService.resolveFromFiscalYear(fiscalYearId)
      if (expectedCompanyId != companyId) {
        return [ok: false, error: "Fiscal year ${fiscalYearId} does not belong to company ${companyId}."]
      }
      YearEndClosingResult result = closingService.closeFiscalYear(fiscalYearId, closingAccount)
      [
          ok: true,
          closed_fiscal_year_id: result.closedFiscalYear?.id,
          closed_fiscal_year_name: result.closedFiscalYear?.name,
          next_fiscal_year_id: result.nextFiscalYear?.id,
          next_fiscal_year_name: result.nextFiscalYear?.name,
          closing_voucher_id: result.closingVoucher?.id,
          closing_voucher_number: result.closingVoucher?.voucherNumber,
          result_account_count: result.resultAccountCount,
          opening_balance_count: result.openingBalanceCount,
          closing_entry_count: result.closingEntryCount,
          net_result: result.netResult,
          warnings: result.warnings
      ]
    } catch (Exception exception) {
      [ok: false, errors: [exception.message ?: exception.class.simpleName]]
    }
  }

  // ---- helpers ----

  private static long requiredLong(Map<String, Object> args, String key) {
    Object value = args.get(key)
    if (value == null) {
      throw new IllegalArgumentException("Missing required argument: ${key}")
    }
    ((Number) value).longValue()
  }

  private static String requiredString(Map<String, Object> args, String key) {
    String value = args.get(key) as String
    if (!value?.trim()) {
      throw new IllegalArgumentException("Missing required argument: ${key}")
    }
    value.trim()
  }

  private static String optionalString(Map<String, Object> args, String key, String defaultValue) {
    String value = args.get(key) as String
    value?.trim() ?: defaultValue
  }

  private static List<Map<String, Object>> linesArg(Map<String, Object> args) {
    Object value = args.get('lines')
    if (!(value instanceof List)) {
      throw new IllegalArgumentException('Missing required argument: lines')
    }
    ((List<Object>) value).collect { Object line ->
      if (!(line instanceof Map)) {
        throw new IllegalArgumentException('Voucher lines must be objects.')
      }
      (Map<String, Object>) line
    }
  }

  protected static String computePreviewToken(Map<String, Object> canonicalPayload) {
    String canonical = JsonOutput.toJson(new TreeMap<>(canonicalPayload))
    MessageDigest.getInstance('SHA-256')
        .digest(canonical.bytes)
        .encodeHex()
        .toString()
  }

  private static String voucherPreviewToken(
      long fiscalYearId,
      String seriesCode,
      String accountingDate,
      String description,
      List<Map<String, Object>> lines
  ) {
    computePreviewToken([
        fiscal_year_id: fiscalYearId,
        series_code: seriesCode,
        accounting_date: accountingDate,
        description: description,
        lines: canonicalVoucherLines(lines)
    ])
  }

  private static String yearEndPreviewToken(long fiscalYearId, String closingAccount) {
    computePreviewToken([
        fiscal_year_id: fiscalYearId,
        closing_account: closingAccount
    ])
  }

  private static List<Map<String, Object>> canonicalVoucherLines(List<Map<String, Object>> lines) {
    List<Map<String, Object>> canonicalLines = []
    lines.each { Map<String, Object> line ->
      Map<String, Object> canonicalLine = [
          account_number: line.get('account_number') as String,
          debit: canonicalAmount(line.get('debit')),
          credit: canonicalAmount(line.get('credit'))
      ]
      canonicalLines << canonicalLine
    }
    canonicalLines
  }

  private VoucherPreview resolveVoucherLines(long companyId, List<Map<String, Object>> rawLines, List<String> errors) {
    List<Map<String, Object>> resolved = []
    BigDecimal totalDebit = BigDecimal.ZERO
    BigDecimal totalCredit = BigDecimal.ZERO
    rawLines.each { Map<String, Object> line ->
      String accountNumber = line.get('account_number') as String
      BigDecimal debit = amount(line.get('debit'))
      BigDecimal credit = amount(line.get('credit'))
      totalDebit += debit
      totalCredit += credit
      Account account = accountNumber == null ? null : accountService.findAccount(companyId, accountNumber)
      if (account == null) {
        errors.add("Konto ${accountNumber ?: '(saknas)'} hittades inte.".toString())
        Map<String, Object> unresolvedLine = [
            account_number: accountNumber,
            error: 'Konto saknas',
            debit: debit,
            credit: credit
        ]
        resolved << unresolvedLine
      } else if (!account.active) {
        errors.add("Konto ${accountNumber} är inaktivt.".toString())
        resolved << lineMap(account, debit, credit, false)
      } else {
        resolved << lineMap(account, debit, credit, true)
      }
    }
    new VoucherPreview(resolved, totalDebit, totalCredit)
  }

  private static Map<String, Object> lineMap(Account account, BigDecimal debit, BigDecimal credit, boolean active) {
    [
        account_number: account.accountNumber,
        account_id: account.id,
        account_name: account.accountName,
        active: active,
        debit: debit,
        credit: credit
    ]
  }

  private static List<VoucherLine> toVoucherLines(List<Map<String, Object>> lines) {
    lines.withIndex().collect { Map<String, Object> line, int index ->
      new VoucherLine(
          null,
          null,
          index,
          ((Number) line.account_id).longValue(),
          line.account_number as String,
          line.account_name as String,
          null,
          amount(line.debit),
          amount(line.credit)
      )
    }
  }

  private static LocalDate parseAccountingDate(String dateText, List<String> errors) {
    try {
      LocalDate.parse(dateText)
    } catch (DateTimeParseException ignored) {
      errors.add("Ogiltigt datumformat: '${dateText}'. Använd YYYY-MM-DD.".toString())
      null
    }
  }

  private static BigDecimal amount(Object value) {
    value == null ? BigDecimal.ZERO : new BigDecimal(value.toString())
  }

  private static String canonicalAmount(Object value) {
    BigDecimal normalized = amount(value).stripTrailingZeros()
    normalized.signum() == 0 ? '0' : normalized.toPlainString()
  }

  private static Map<String, Object> intParam(String description) {
    [type: 'integer', description: description]
  }

  private static Map<String, Object> optIntParam(String description) {
    [type: 'integer', description: description]
  }

  private static Map<String, Object> optStrParam(String description) {
    [type: 'string', description: description]
  }

  private static Map<String, Object> strParam(String description) {
    [type: 'string', description: description]
  }

  private static Map<String, Object> voucherLinesParam() {
    [
        type: 'array',
        description: 'Voucher lines. Each line: { account_number, debit, credit }. VAT is derived from the account vat_code; per-line vat_code is not accepted in Phase 3.',
        items: [
            type: 'object',
            properties: [
                account_number: [type: 'string', description: 'Account number to post against.'],
                debit: numberParam('Debit amount. Use 0 when this is a credit line.'),
                credit: numberParam('Credit amount. Use 0 when this is a debit line.')
            ],
            required: ['account_number', 'debit', 'credit']
        ]
    ]
  }

  private static Map<String, Object> numberParam(String description = 'Monetary amount') {
    [type: 'number', description: description]
  }

  private static Map<String, Object> toolDef(
      String name,
      String description,
      List<String> required,
      Map<String, Object> properties
  ) {
    [
        name: name,
        description: description,
        inputSchema: [
            type: 'object',
            properties: properties,
            required: required
        ]
    ]
  }

  private static final class VoucherPreview {

    final List<Map<String, Object>> lines
    final BigDecimal totalDebit
    final BigDecimal totalCredit

    private VoucherPreview(List<Map<String, Object>> lines, BigDecimal totalDebit, BigDecimal totalCredit) {
      this.lines = lines
      this.totalDebit = totalDebit
      this.totalCredit = totalCredit
    }
  }
}
