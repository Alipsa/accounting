package se.alipsa.accounting.mcp

/**
 * Builds MCP tool schemas exposed by the local accounting MCP server.
 */
final class McpToolDefinitions {

  private McpToolDefinitions() {
  }

  static List<Map<String, Object>> listTools() {
    readOnlyToolDefs() + voucherToolDefs() + vatWriteToolDefs() + yearEndToolDefs() + sieToolDefs()
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
            'Validates a voucher proposal without posting it. Returns resolved accounts, balance check, and any errors or warnings. To place a proposal in the application, use set_active_voucher_draft; the user must then save it in the GUI. VAT reporting uses VAT codes configured on accounts; per-line vat_code is not accepted in Phase 3.',
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
        toolDef('get_active_voucher_draft',
            'Gets the unsaved voucher currently displayed in the desktop application.',
            [], [:]
        ),
        toolDef('set_active_voucher_draft',
            'Replaces the unsaved GUI voucher draft. This never saves; the user must review and press Save in the application.',
            ['accounting_date', 'description', 'lines'],
            [
                accounting_date: strParam('Accounting date in ISO format YYYY-MM-DD'),
                description: strParam('Voucher description'),
                series_code: optStrParam('Voucher series code. Defaults to A.'),
                lines: voucherLinesParam()
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

  private static List<Map<String, Object>> sieToolDefs() {
    [
        toolDef('preview_sie_import',
            'Previews a SIE4 import without writing data. Returns file summary, duplicate status, replacement purge summary, blocking issues, and import_token when importable.',
            ['company_id', 'file_path'],
            [
                company_id: intParam('Company ID'),
                file_path: strParam('Absolute path to the SIE file.'),
                replace_existing: optBoolParam('Whether to replace the existing matching fiscal year. Defaults to false.')
            ]
        ),
        toolDef('import_sie',
            'Imports a SIE4 file. Run preview_sie_import first and pass back its import_token for the same file and replace_existing value.',
            ['company_id', 'file_path', 'import_token'],
            [
                company_id: intParam('Company ID'),
                file_path: strParam('Absolute path to the SIE file.'),
                import_token: strParam('Token returned by preview_sie_import.'),
                replace_existing: optBoolParam('Whether to replace the existing matching fiscal year. Defaults to false.')
            ]
        ),
        toolDef('export_sie',
            'Exports a fiscal year as SIE4. Refuses to overwrite existing files unless overwrite is true.',
            ['fiscal_year_id'],
            [
                fiscal_year_id: intParam('Fiscal year ID to export.'),
                output_path: optStrParam('Optional absolute output path. Defaults to the application SIE export directory.'),
                overwrite: optBoolParam('Allow overwriting an existing output file. Defaults to false.')
            ]
        ),
        toolDef('list_import_jobs',
            'Lists recent SIE import jobs for the given company. Limit defaults to 20 and is clamped to 1..50.',
            ['company_id'],
            [
                company_id: intParam('Company ID'),
                limit: optIntParam('Max rows returned. Default 20, max 50.')
            ]
        )
    ]
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

  private static Map<String, Object> optBoolParam(String description) {
    [type: 'boolean', description: description]
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
}
