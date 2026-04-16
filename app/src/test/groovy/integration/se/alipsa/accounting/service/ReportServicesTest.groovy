package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertArrayEquals
import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

import groovy.sql.Sql

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.VatCode
import se.alipsa.accounting.domain.VoucherLine
import se.alipsa.accounting.domain.report.ReportArchive
import se.alipsa.accounting.domain.report.ReportResult
import se.alipsa.accounting.domain.report.ReportSelection
import se.alipsa.accounting.domain.report.ReportType
import se.alipsa.accounting.support.AppPaths
import se.alipsa.accounting.support.I18n

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

class ReportServicesTest {

  @TempDir
  Path tempDir

  private DatabaseService databaseService
  private AuditLogService auditLogService
  private AccountingPeriodService accountingPeriodService
  private FiscalYearService fiscalYearService
  private VoucherService voucherService
  private ReportDataService reportDataService
  private ReportArchiveService reportArchiveService
  private ReportExportService reportExportService
  private JournoReportService journoReportService
  private FiscalYear fiscalYear
  private String previousHome
  private Locale previousLocale

  @BeforeEach
  void setUp() {
    previousLocale = I18n.instance.locale
    I18n.instance.setLocale(Locale.forLanguageTag('sv'))
    previousHome = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, tempDir.toString())
    databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    auditLogService = new AuditLogService(databaseService)
    accountingPeriodService = new AccountingPeriodService(databaseService, auditLogService)
    fiscalYearService = new FiscalYearService(databaseService, accountingPeriodService, auditLogService)
    voucherService = new VoucherService(databaseService, auditLogService)
    reportDataService = new ReportDataService(databaseService, fiscalYearService, accountingPeriodService)
    reportArchiveService = new ReportArchiveService(databaseService)
    reportExportService = new ReportExportService(
        reportDataService,
        reportArchiveService,
        new ReportIntegrityService(new AttachmentService(databaseService, auditLogService), auditLogService),
        auditLogService,
        databaseService
    )
    journoReportService = new JournoReportService(
        reportDataService,
        reportArchiveService,
        new ReportIntegrityService(new AttachmentService(databaseService, auditLogService), auditLogService),
        new CompanyService(databaseService),
        auditLogService,
        databaseService
    )
    fiscalYear = fiscalYearService.createFiscalYear(CompanyService.LEGACY_COMPANY_ID, '2026', LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
    insertTestAccounts()
    bookFixtures()
  }

  @AfterEach
  void tearDown() {
    I18n.instance.setLocale(previousLocale)
    restoreProperty(AppPaths.HOME_OVERRIDE_PROPERTY, previousHome)
  }

  @Test
  void csvExportUsesSameRowsAsPreviewAndArchivesTheArtifact() {
    ReportSelection selection = new ReportSelection(
        ReportType.VOUCHER_LIST,
        fiscalYear.id,
        null,
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 1, 31)
    )

    ReportResult preview = reportDataService.generate(selection)
    byte[] expectedCsv = reportExportService.renderCsv(preview)
    ReportArchive archive = reportExportService.exportCsv(selection)
    byte[] archivedCsv = reportArchiveService.readArchive(archive.id)

    assertArrayEquals(expectedCsv, archivedCsv)
    assertEquals('CSV', archive.reportFormat)
    assertEquals(ReportType.VOUCHER_LIST, archive.reportType)
    assertEquals(
        '''﻿Datum;Verifikation;Serie;Text;Status;Debet;Kredit
2026-01-10;A-1;A;Försäljning januari;ACTIVE;1250.00;1250.00
2026-01-18;A-2;A;Leverantörsfaktura;ACTIVE;250.00;250.00
''',
        new String(archivedCsv, StandardCharsets.UTF_8)
    )
    assertTrue(Files.isRegularFile(reportArchiveService.resolveStoredPath(archive)))
  }

  @Test
  void pdfGenerationProducesArchivedPdfFile() {
    ReportSelection selection = new ReportSelection(
        ReportType.INCOME_STATEMENT,
        fiscalYear.id,
        null,
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 1, 31)
    )

    ReportResult preview = reportDataService.generate(selection)
    ReportArchive archive = journoReportService.generatePdf(selection)
    byte[] pdf = reportArchiveService.readArchive(archive.id)

    assertEquals('PDF', archive.reportFormat)
    assertEquals(ReportType.INCOME_STATEMENT, archive.reportType)
    assertTrue(new String(pdf, 0, 5, StandardCharsets.US_ASCII).startsWith('%PDF-'))
    assertTrue(Files.size(reportArchiveService.resolveStoredPath(archive)) > 500L)
    String html = journoReportService.renderHtml(preview).replace('\r\n', '\n').replace('\r', '\n')
    assertTrue(html.contains('Resultatrapport'))
    assertTrue(html.contains('Post'))
    assertTrue(html.contains('Belopp'))
  }

  @Test
  void generalLedgerCsvMatchesGoldenMaster() {
    ReportSelection selection = new ReportSelection(
        ReportType.GENERAL_LEDGER,
        fiscalYear.id,
        null,
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 1, 31)
    )

    ReportArchive archive = reportExportService.exportCsv(selection)
    String csv = new String(reportArchiveService.readArchive(archive.id), StandardCharsets.UTF_8)

    assertEquals(
        '''﻿Konto;Namn;Datum;Verifikation;Text;Debet;Kredit;Saldo
1510;Kundfordringar;;;Ingående balans;0.00;0.00;0.00
1510;Kundfordringar;2026-01-10;A-1;Kundfordran;1250.00;0.00;1250.00
2440;Leverantörsskulder;;;Ingående balans;0.00;0.00;0.00
2440;Leverantörsskulder;2026-01-18;A-2;Leverantörsskuld;0.00;250.00;250.00
2611;Utgående moms 25%;;;Ingående balans;0.00;0.00;0.00
2611;Utgående moms 25%;2026-01-10;A-1;Utgående moms;0.00;250.00;250.00
2641;Debiterad ingående moms;;;Ingående balans;0.00;0.00;0.00
2641;Debiterad ingående moms;2026-01-18;A-2;Ingående moms;50.00;0.00;50.00
3010;Försäljning;;;Ingående balans;0.00;0.00;0.00
3010;Försäljning;2026-01-10;A-1;Försäljning;0.00;1000.00;1000.00
4010;Varuinköp;;;Ingående balans;0.00;0.00;0.00
4010;Varuinköp;2026-01-18;A-2;Varuinköp;200.00;0.00;200.00
''',
        csv
    )
  }

  @Test
  void csvExportAddsBomAndEscapesFormulaLookingCells() {
    voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 1, 20),
        '=2+2',
        [
            new VoucherLine(null, null, 0, null, '1510', null, 'Kundfordran', 10.00G, 0.00G),
            new VoucherLine(null, null, 0, null, '3010', null, 'Försäljning', 0.00G, 8.00G),
            new VoucherLine(null, null, 0, null, '2611', null, 'Utgående moms', 0.00G, 2.00G)
        ]
    )
    ReportSelection selection = new ReportSelection(
        ReportType.VOUCHER_LIST,
        fiscalYear.id,
        null,
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 1, 31)
    )

    String csv = new String(reportExportService.renderCsv(reportDataService.generate(selection)), StandardCharsets.UTF_8)

    assertTrue(csv.startsWith('\uFEFF'))
    assertTrue(csv.contains("2026-01-20;A-3;A;'=2+2;ACTIVE;10.00;10.00"))
  }

  @Test
  void generalLedgerUsesPriorBalancesForMidYearSelections() {
    insertOpeningBalance('1510', 100.00G)
    voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 2, 10),
        'Försäljning februari',
        [
            new VoucherLine(null, null, 0, null, '1510', null, 'Kundfordran', 125.00G, 0.00G),
            new VoucherLine(null, null, 0, null, '3010', null, 'Försäljning', 0.00G, 100.00G),
            new VoucherLine(null, null, 0, null, '2611', null, 'Utgående moms', 0.00G, 25.00G)
        ]
    )

    ReportResult report = reportDataService.generate(new ReportSelection(
        ReportType.GENERAL_LEDGER,
        fiscalYear.id,
        null,
        LocalDate.of(2026, 2, 1),
        LocalDate.of(2026, 2, 28)
    ))

    assertTrue(report.tableRows.contains(['1510', 'Kundfordringar', '', '', 'Ingående balans', '0.00', '0.00', '1350.00']))
    assertTrue(report.tableRows.contains(['1510', 'Kundfordringar', '2026-02-10', 'A-3', 'Kundfordran', '125.00', '0.00', '1475.00']))
  }

  @Test
  void trialBalanceKeepsDebitAndCreditTotalsBalanced() {
    ReportResult report = reportDataService.generate(new ReportSelection(
        ReportType.TRIAL_BALANCE,
        fiscalYear.id,
        null,
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 1, 31)
    ))

    BigDecimal debitTotal = report.tableRows.sum(BigDecimal.ZERO) { List<String> row -> new BigDecimal(row[3]) } as BigDecimal
    BigDecimal creditTotal = report.tableRows.sum(BigDecimal.ZERO) { List<String> row -> new BigDecimal(row[4]) } as BigDecimal

    assertEquals(1500.00G, debitTotal)
    assertEquals(debitTotal, creditTotal)
  }

  @Test
  void balanceSheetBalancesAssetsAgainstLiabilitiesAndEquityWhenSelectionHasOnlyOpeningBalances() {
    insertOpeningBalance('1510', 1000.00G)
    insertOpeningBalance('2010', 1000.00G)

    ReportResult report = reportDataService.generate(new ReportSelection(
        ReportType.BALANCE_SHEET,
        fiscalYear.id,
        null,
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 1, 1)
    ))

    BigDecimal assets = sumSection(report, 'Tillgångar')
    BigDecimal liabilities = sumSection(report, 'Skulder')
    BigDecimal equity = sumSection(report, 'Eget kapital')

    assertEquals(1000.00G, assets)
    assertEquals(1000.00G, liabilities + equity)
  }

  @Test
  void vatReportExcludesTransferVoucherRowsAfterVatPeriodIsLocked() {
    VatService vatService = new VatService(databaseService, voucherService)
    def january = vatService.listPeriods(fiscalYear.id).first()
    vatService.reportPeriod(january.id)
    vatService.bookTransfer(january.id)

    ReportResult report = reportDataService.generate(new ReportSelection(
        ReportType.VAT_REPORT,
        fiscalYear.id,
        null,
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 1, 31)
    ))

    assertEquals(2, report.tableRows.size())
    assertTrue(report.tableRows.contains(['OUTPUT_25', 'Utgående moms 25 %', '1000.00', '250.00', '0.00']))
    assertTrue(report.tableRows.contains(['INPUT_25', 'Ingående moms 25 %', '200.00', '0.00', '50.00']))
  }

  @Test
  void reportSelectionOutsideFiscalYearIsRejected() {
    assertThrows(IllegalArgumentException) {
      reportDataService.generate(new ReportSelection(
          ReportType.VOUCHER_LIST,
          fiscalYear.id,
          null,
          LocalDate.of(2025, 12, 31),
          LocalDate.of(2026, 1, 31)
      ))
    }
  }

  @Test
  void pathTraversalAttemptInArchivedMetadataIsRejected() {
    ReportSelection selection = new ReportSelection(
        ReportType.VOUCHER_LIST,
        fiscalYear.id,
        null,
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 1, 31)
    )
    ReportArchive archive = reportExportService.exportCsv(selection)

    databaseService.withTransaction { Sql sql ->
      sql.executeUpdate("update report_archive set storage_path = '../../../etc/passwd' where id = ?", [archive.id])
    }

    SecurityException exception = assertThrows(SecurityException) {
      reportArchiveService.readArchive(archive.id)
    }

    assertTrue(exception.message.contains('utanför rapportarkivet'))
  }

  @Test
  void incomeStatementProducesGroupedSectionsWithSubtotals() {
    ReportResult report = reportDataService.generate(new ReportSelection(
        ReportType.INCOME_STATEMENT,
        fiscalYear.id,
        null,
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 1, 31)
    ))

    // Should have rows for: Nettoomsättning, Summa rörelseintäkter,
    // Råvaror, Summa rörelsekostnader, Rörelseresultat, Årets resultat
    assertTrue(report.tableRows.size() >= 4)

    // Check that summary lines contain result figures
    assertTrue(report.summaryLines.any { String line -> line.contains('Rörelseresultat') })
    assertTrue(report.summaryLines.any { String line -> line.contains('Årets resultat') })

    // Verify the net result: income 1000 - expenses 200 = 800
    Map<String, Object> model = report.templateModel
    assertEquals(800.00G, model.result)
  }

  private void bookFixtures() {
    voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 1, 10),
        'Försäljning januari',
        [
            new VoucherLine(null, null, 0, null, '1510', null, 'Kundfordran', 1250.00G, 0.00G),
            new VoucherLine(null, null, 0, null, '3010', null, 'Försäljning', 0.00G, 1000.00G),
            new VoucherLine(null, null, 0, null, '2611', null, 'Utgående moms', 0.00G, 250.00G)
        ]
    )
    voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 1, 18),
        'Leverantörsfaktura',
        [
            new VoucherLine(null, null, 0, null, '4010', null, 'Varuinköp', 200.00G, 0.00G),
            new VoucherLine(null, null, 0, null, '2641', null, 'Ingående moms', 50.00G, 0.00G),
            new VoucherLine(null, null, 0, null, '2440', null, 'Leverantörsskuld', 0.00G, 250.00G)
        ]
    )
  }

  private void insertTestAccounts() {
    databaseService.withTransaction { Sql sql ->
      insertAccount(sql, '2010', 'Eget kapital', 'EQUITY', 'CREDIT', null, 'EQUITY')
      insertAccount(sql, '1510', 'Kundfordringar', 'ASSET', 'DEBIT', null, 'RECEIVABLES')
      insertAccount(sql, '2440', 'Leverantörsskulder', 'LIABILITY', 'CREDIT', null, 'SHORT_TERM_LIABILITIES_CREDIT')
      insertAccount(sql, '2650', 'Redovisningskonto för moms', 'LIABILITY', 'CREDIT', null, 'VAT_AND_EXCISE')
      insertAccount(sql, '2611', 'Utgående moms 25%', 'LIABILITY', 'CREDIT', VatCode.OUTPUT_25.name(), 'VAT_AND_EXCISE')
      insertAccount(sql, '2641', 'Debiterad ingående moms', 'ASSET', 'DEBIT', VatCode.INPUT_25.name(), 'VAT_AND_EXCISE')
      insertAccount(sql, '3010', 'Försäljning', 'INCOME', 'CREDIT', VatCode.OUTPUT_25.name(), 'NET_REVENUE')
      insertAccount(sql, '4010', 'Varuinköp', 'EXPENSE', 'DEBIT', VatCode.INPUT_25.name(), 'RAW_MATERIALS')
    }
  }

  private void insertOpeningBalance(String accountNumber, BigDecimal amount) {
    databaseService.withTransaction { Sql sql ->
      def accountRow = sql.firstRow('select id from account where account_number = ?', [accountNumber])
      sql.executeInsert('''
          insert into opening_balance (
              fiscal_year_id,
              account_id,
              amount,
              created_at,
              updated_at
          ) values (?, ?, ?, current_timestamp, current_timestamp)
      ''', [fiscalYear.id, accountRow.id, amount])
    }
  }

  private static BigDecimal sumSection(ReportResult report, String section) {
    report.tableRows.findAll { List<String> row ->
      row[0] == section
    }.sum(BigDecimal.ZERO) { List<String> row ->
      new BigDecimal(row[3])
    } as BigDecimal
  }

  private static void insertAccount(
      Sql sql,
      String accountNumber,
      String accountName,
      String accountClass,
      String normalBalanceSide,
      String vatCode,
      String accountSubgroup
  ) {
    sql.executeInsert('''
        insert into account (
            company_id,
            account_number,
            account_name,
            account_class,
            normal_balance_side,
            vat_code,
            account_subgroup,
            active,
            manual_review_required,
            classification_note,
            created_at,
            updated_at
        ) values (1, ?, ?, ?, ?, ?, ?, true, false, null, current_timestamp, current_timestamp)
    ''', [accountNumber, accountName, accountClass, normalBalanceSide, vatCode, accountSubgroup])
  }

  private static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name)
      return
    }
    System.setProperty(name, value)
  }
}
