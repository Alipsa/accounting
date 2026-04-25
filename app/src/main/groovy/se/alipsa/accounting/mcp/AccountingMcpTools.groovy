package se.alipsa.accounting.mcp

import groovy.json.JsonOutput

import se.alipsa.accounting.domain.Company
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.VatPeriod
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

import java.security.MessageDigest
import java.time.LocalDate

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
    List<se.alipsa.accounting.domain.Account> accounts =
        accountService.searchAccounts(companyId, query, null, true, false)
    [
        ok: true,
        accounts: accounts.collect { se.alipsa.accounting.domain.Account a ->
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
    List<se.alipsa.accounting.domain.Voucher> vouchers =
        voucherService.listVouchers(companyId, fiscalYearId, null, null)
    [
        ok: true,
        vouchers: vouchers.take(200).collect { se.alipsa.accounting.domain.Voucher v ->
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

  // ---- helpers ----

  private static long requiredLong(Map<String, Object> args, String key) {
    Object value = args.get(key)
    if (value == null) {
      throw new IllegalArgumentException("Missing required argument: ${key}")
    }
    ((Number) value).longValue()
  }

  protected static String computePreviewToken(Map<String, Object> canonicalPayload) {
    String canonical = JsonOutput.toJson(new TreeMap<>(canonicalPayload))
    MessageDigest.getInstance('SHA-256')
        .digest(canonical.bytes)
        .encodeHex()
        .toString()
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
}
