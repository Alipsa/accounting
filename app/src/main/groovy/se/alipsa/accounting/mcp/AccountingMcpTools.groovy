package se.alipsa.accounting.mcp

import groovy.json.JsonOutput

import se.alipsa.accounting.domain.Account
import se.alipsa.accounting.domain.Company
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.ImportJob
import se.alipsa.accounting.domain.VatPeriod
import se.alipsa.accounting.domain.Voucher
import se.alipsa.accounting.domain.report.GeneralLedgerRow
import se.alipsa.accounting.domain.report.ReportSelection
import se.alipsa.accounting.domain.report.ReportType
import se.alipsa.accounting.domain.report.TrialBalanceRow
import se.alipsa.accounting.service.AccountService
import se.alipsa.accounting.service.ClosingService
import se.alipsa.accounting.service.CompanyService
import se.alipsa.accounting.service.FiscalYearPurgeSummary
import se.alipsa.accounting.service.FiscalYearService
import se.alipsa.accounting.service.ReportDataService
import se.alipsa.accounting.service.SieExportResult
import se.alipsa.accounting.service.SieImportExportService
import se.alipsa.accounting.service.SieImportPreview
import se.alipsa.accounting.service.VatService
import se.alipsa.accounting.service.VoucherService
import se.alipsa.accounting.service.YearEndClosingPreview
import se.alipsa.accounting.service.YearEndClosingResult
import se.alipsa.accounting.support.AppPaths

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.ConcurrentHashMap

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
  private final SieImportExportService sieImportExportService
  private VoucherDraftAccess voucherDraftAccess
  // Tokens remain consumed for this desktop session so a disconnected client cannot replay a write.
  private final Set<String> consumedPreviewTokens = ConcurrentHashMap.newKeySet()

  AccountingMcpTools() {
    this(
        new CompanyService(),
        new FiscalYearService(),
        new AccountService(),
        new VoucherService(),
        new VatService(),
        new ClosingService(),
        new ReportDataService(),
        new SieImportExportService()
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
    this(
        companyService,
        fiscalYearService,
        accountService,
        voucherService,
        vatService,
        closingService,
        reportDataService,
        new SieImportExportService()
    )
  }

  AccountingMcpTools(
      CompanyService companyService,
      FiscalYearService fiscalYearService,
      AccountService accountService,
      VoucherService voucherService,
      VatService vatService,
      ClosingService closingService,
      ReportDataService reportDataService,
      SieImportExportService sieImportExportService
  ) {
    this.companyService = companyService
    this.fiscalYearService = fiscalYearService
    this.accountService = accountService
    this.voucherService = voucherService
    this.vatService = vatService
    this.closingService = closingService
    this.reportDataService = reportDataService
    this.sieImportExportService = sieImportExportService
  }

  List<Map<String, Object>> listTools() {
    McpToolDefinitions.listTools()
  }

  void setVoucherDraftAccess(VoucherDraftAccess value) {
    voucherDraftAccess = value
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
      case 'get_active_voucher_draft':
        return getActiveVoucherDraft()
      case 'set_active_voucher_draft':
        return setActiveVoucherDraft(arguments)
      case 'create_correction_voucher':
        return createCorrectionVoucher(arguments)
      case 'preview_year_end':
        return previewYearEnd(arguments)
      case 'close_fiscal_year':
        return closeFiscalYear(arguments)
      case 'preview_sie_import':
        return previewSieImport(arguments)
      case 'import_sie':
        return importSie(arguments)
      case 'export_sie':
        return exportSie(arguments)
      case 'list_import_jobs':
        return listImportJobs(arguments)
      default:
        throw new IllegalArgumentException("Unknown MCP tool: ${name}")
    }
  }

  // ---- read-only tools ----

  private Map<String, Object> getActiveVoucherDraft() {
    if (voucherDraftAccess == null) {
      return voucherEditorUnavailable()
    }
    Map<String, Object> draft = voucherDraftAccess.getVoucherDraft()
    if (draft == null) {
      return [ok: false, error: 'No unsaved voucher draft is active.']
    }
    [ok: true, saved: false, draft: draft]
  }

  private Map<String, Object> setActiveVoucherDraft(Map<String, Object> args) {
    if (voucherDraftAccess == null) {
      return voucherEditorUnavailable()
    }
    voucherDraftAccess.setVoucherDraft(args)
    [ok: true, saved: false, awaiting_user_save: true]
  }

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
            accounting_method: company.accountingMethod?.name(),
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
        return [ok: false, errors: ["VAT period ${vatPeriodId} does not belong to company ${companyId}.".toString()]]
      }
      VatService.VatReport report = vatService.calculateReport(vatPeriodId)
      String expectedHash = VatService.calculateReportHash(report)
      if (providedHash != expectedHash) {
        return [ok: false, errors: ['report_hash stämmer inte med aktuell momsrapport — kör get_vat_report igen.']]
      }
      if (!consumePreviewToken("vat:${providedHash}")) {
        return [ok: false, errors: ['report_hash har redan använts — kör get_vat_report igen.']]
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
        return [ok: false, errors: ["Fiscal year ${fiscalYearId} does not belong to company ${companyId}.".toString()]]
      }
      YearEndClosingPreview preview = closingService.previewClosing(fiscalYearId, closingAccount)
      boolean readyToClose = preview.blockingIssues.isEmpty()
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
          ready_to_close: readyToClose,
          preview_token: readyToClose ? yearEndPreviewToken(fiscalYearId, preview.closingAccountNumber) : null
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
    try {
      long expectedCompanyId = companyService.resolveFromFiscalYear(fiscalYearId)
      if (expectedCompanyId != companyId) {
        return [ok: false, errors: ["Fiscal year ${fiscalYearId} does not belong to company ${companyId}.".toString()]]
      }
      String expectedToken = yearEndPreviewToken(fiscalYearId, closingAccount)
      if (providedToken != expectedToken) {
        return [ok: false, errors: ['preview_token stämmer inte — kör preview_year_end igen.']]
      }
      if (!consumePreviewToken("year-end:${providedToken}")) {
        return [ok: false, errors: ['preview_token har redan använts — kör preview_year_end igen.']]
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

  private Map<String, Object> previewSieImport(Map<String, Object> args) {
    long companyId = requiredLong(args, 'company_id')
    Path filePath = Path.of(requiredString(args, 'file_path'))
    boolean replaceExisting = optionalBoolean(args, 'replace_existing', false)
    try {
      SieImportPreview preview = sieImportExportService.previewSieImport(companyId, filePath, replaceExisting)
      Map<String, Object> result = previewMap(preview)
      result.import_token = preview.blockingIssues.isEmpty() ? sieImportToken(companyId, preview) : null
      result.ok = preview.blockingIssues.isEmpty()
      result
    } catch (Exception exception) {
      [ok: false, errors: [exception.message ?: exception.class.simpleName]]
    }
  }

  private Map<String, Object> importSie(Map<String, Object> args) {
    long companyId = requiredLong(args, 'company_id')
    Path filePath = Path.of(requiredString(args, 'file_path'))
    boolean replaceExisting = optionalBoolean(args, 'replace_existing', false)
    String providedToken = args.get('import_token') as String
    if (!providedToken) {
      return [ok: false, errors: ['import_token krävs — kör preview_sie_import med exakt samma fil och replace_existing-värde först.']]
    }

    try {
      SieImportPreview preview = sieImportExportService.previewSieImport(companyId, filePath, replaceExisting)
      if (!preview.blockingIssues.isEmpty()) {
        return [ok: false, errors: preview.blockingIssues]
      }
      String expectedToken = sieImportToken(companyId, preview)
      if (providedToken != expectedToken) {
        return [
            ok: false,
            errors: [
                replaceExisting
                    ? 'Räkenskapsårets innehåll har förändrats sedan förhandsgranskningen — kör preview_sie_import igen.'
                    : 'Ogiltig import_token — kör preview_sie_import med exakt samma fil och replace_existing-värde.'
            ]
        ]
      }
      if (!consumePreviewToken("sie:${providedToken}")) {
        return [ok: false, errors: ['import_token har redan använts — kör preview_sie_import igen.']]
      }
      def result = replaceExisting
          ? sieImportExportService.replaceFiscalYear(companyId, filePath)
          : sieImportExportService.importFile(companyId, filePath)
      [
          ok: true,
          duplicate: result.duplicate,
          fiscal_year_id: result.fiscalYear?.id ?: result.job?.fiscalYearId,
          fiscal_year_name: result.fiscalYear?.name,
          accounts_created: result.accountsCreated,
          opening_balance_count: result.openingBalanceCount,
          voucher_count: result.voucherCount,
          line_count: result.lineCount,
          warnings: result.warnings,
          job_id: result.job?.id,
          job_status: result.job?.status?.name(),
          summary: result.job?.summary
      ]
    } catch (Exception exception) {
      [ok: false, errors: [exception.message ?: exception.class.simpleName]]
    }
  }

  private Map<String, Object> exportSie(Map<String, Object> args) {
    long fiscalYearId = requiredLong(args, 'fiscal_year_id')
    boolean overwrite = optionalBoolean(args, 'overwrite', false)
    try {
      Path outputPath = args.get('output_path') == null
          ? defaultSieExportPath(fiscalYearId)
          : Path.of(args.get('output_path') as String).toAbsolutePath().normalize()
      if (Files.exists(outputPath) && !overwrite) {
        return [
            ok: false,
            file_exists: true,
            existing_file_path: outputPath.toString(),
            errors: ['Målfilen finns redan. Bekräfta överskrivning och anropa export_sie med overwrite: true.']
        ]
      }
      SieExportResult result = sieImportExportService.exportFiscalYear(fiscalYearId, outputPath)
      [
          ok: true,
          file_path: result.filePath?.toString(),
          checksum_sha256: result.checksumSha256,
          file_size_bytes: result.fileSizeBytes,
          account_count: result.accountCount,
          opening_balance_count: result.openingBalanceCount,
          voucher_count: result.voucherCount
      ]
    } catch (Exception exception) {
      [ok: false, errors: [exception.message ?: exception.class.simpleName]]
    }
  }

  private Map<String, Object> listImportJobs(Map<String, Object> args) {
    long companyId = requiredLong(args, 'company_id')
    int limit = args.get('limit') != null
        ? Math.min(Math.max(((Number) args.get('limit')).intValue(), 1), 50)
        : 20
    try {
      [
          ok: true,
          import_jobs: sieImportExportService.listImportJobs(companyId, limit).collect { ImportJob job ->
            [
                id: job.id,
                file_name: job.fileName,
                status: job.status?.name(),
                summary: job.summary,
                fiscal_year_id: job.fiscalYearId,
                started_at: job.startedAt?.toString(),
                completed_at: job.completedAt?.toString()
            ]
          }
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

  private static boolean optionalBoolean(Map<String, Object> args, String key, boolean defaultValue) {
    Object value = args.get(key)
    value == null ? defaultValue : Boolean.valueOf(value.toString())
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

  private boolean consumePreviewToken(String token) {
    consumedPreviewTokens.add(token)
  }

  private static Map<String, Object> voucherEditorUnavailable() {
    [ok: false, error: 'Voucher editor is not available.']
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

  private static String sieImportToken(long companyId, SieImportPreview preview) {
    computePreviewToken(sieImportTokenPayload(companyId, preview))
  }

  private static Map<String, Object> sieImportTokenPayload(long companyId, SieImportPreview preview) {
    Map<String, Object> payload = [
        company_id: companyId,
        file_checksum: preview.checksumSha256,
        replace_existing: preview.replaceExisting
    ]
    if (preview.replaceExisting) {
      FiscalYearPurgeSummary summary = preview.purgeSummary
      payload.putAll([
          target_fiscal_year_id: preview.targetFiscalYearId,
          purge_voucher_count: summary?.voucherCount ?: 0,
          purge_attachment_count: summary?.attachmentCount ?: 0,
          purge_opening_balance_count: summary?.openingBalanceCount ?: 0,
          purge_vat_period_count: summary?.vatPeriodCount ?: 0,
          purge_report_archive_count: summary?.reportArchiveCount ?: 0,
          purge_audit_log_count: summary?.auditLogCount ?: 0
      ])
    }
    payload
  }

  private Map<String, Object> previewMap(SieImportPreview preview) {
    [
        company_name_in_file: preview.companyNameInFile,
        fiscal_year_start: preview.fiscalYearStart?.toString(),
        fiscal_year_end: preview.fiscalYearEnd?.toString(),
        account_count: preview.accountCount,
        voucher_count: preview.voucherCount,
        line_count: preview.lineCount,
        warnings: preview.warnings,
        checksum_sha256: preview.checksumSha256,
        replace_existing: preview.replaceExisting,
        fiscal_year_exists: preview.fiscalYearExists,
        target_fiscal_year_id: preview.targetFiscalYearId,
        target_fiscal_year_name: preview.targetFiscalYearName,
        purge_summary: preview.purgeSummary == null ? null : purgeSummaryMap(preview.purgeSummary),
        blocking_issues: preview.blockingIssues,
        is_duplicate: preview.duplicate,
        duplicate_job_id: preview.duplicateJobId
    ]
  }

  private static Map<String, Object> purgeSummaryMap(FiscalYearPurgeSummary summary) {
    [
        attachment_count: summary.attachmentCount,
        report_archive_count: summary.reportArchiveCount,
        opening_balance_count: summary.openingBalanceCount,
        voucher_count: summary.voucherCount,
        vat_period_count: summary.vatPeriodCount,
        audit_log_count: summary.auditLogCount
    ]
  }

  private Path defaultSieExportPath(long fiscalYearId) {
    FiscalYear fiscalYear = fiscalYearService.findById(fiscalYearId)
    if (fiscalYear == null) {
      throw new IllegalArgumentException("Okänt räkenskapsår: ${fiscalYearId}")
    }
    String safeName = fiscalYear.name
        .replaceAll(/[^A-Za-z0-9._-]+/, '-')
        .replaceAll(/^-+|-+$/, '')
    if (!safeName) {
      safeName = fiscalYearId.toString()
    }
    String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern('yyyyMMddHHmm'))
    AppPaths.sieExportsDirectory().resolve("AlipsaAccounting-${safeName}-${timestamp}.sie").toAbsolutePath().normalize()
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
