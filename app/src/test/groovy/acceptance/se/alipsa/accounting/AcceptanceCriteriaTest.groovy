package se.alipsa.accounting

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

import groovy.sql.Sql

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.CompanySettings
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.VatCode
import se.alipsa.accounting.domain.VatPeriodicity
import se.alipsa.accounting.domain.Voucher
import se.alipsa.accounting.domain.VoucherLine
import se.alipsa.accounting.domain.report.ReportArchive
import se.alipsa.accounting.domain.report.ReportSelection
import se.alipsa.accounting.domain.report.ReportType
import se.alipsa.accounting.service.AccountService
import se.alipsa.accounting.service.AccountingPeriodService
import se.alipsa.accounting.service.AttachmentService
import se.alipsa.accounting.service.AuditLogService
import se.alipsa.accounting.service.BackupService
import se.alipsa.accounting.service.BackupSummary
import se.alipsa.accounting.service.ChartOfAccountsImportService
import se.alipsa.accounting.service.ClosingService
import se.alipsa.accounting.service.CompanyService
import se.alipsa.accounting.service.CompanySettingsService
import se.alipsa.accounting.service.DatabaseService
import se.alipsa.accounting.service.FiscalYearService
import se.alipsa.accounting.service.JournoReportService
import se.alipsa.accounting.service.MigrationService
import se.alipsa.accounting.service.ReportArchiveService
import se.alipsa.accounting.service.ReportDataService
import se.alipsa.accounting.service.ReportExportService
import se.alipsa.accounting.service.ReportIntegrityService
import se.alipsa.accounting.service.SieImportExportService
import se.alipsa.accounting.service.VatService
import se.alipsa.accounting.service.VoucherService
import se.alipsa.accounting.service.YearEndClosingResult
import se.alipsa.accounting.support.AppPaths

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

class AcceptanceCriteriaTest {

  @TempDir
  Path tempDir

  private String previousHome

  @BeforeEach
  void setUp() {
    previousHome = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, tempDir.resolve('source-home').toString())
  }

  @AfterEach
  void tearDown() {
    if (previousHome == null) {
      System.clearProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    } else {
      System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, previousHome)
    }
  }

  @Test
  void acceptanceCriteriaOneToTenCanBeDemonstrated() {
    DatabaseService databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    AcceptanceServices services = createAcceptanceServices(databaseService)

    services.companySettingsService.save(new CompanySettings(1L, 'Accept AB', '556677-8899', 'SEK', 'sv-SE', VatPeriodicity.MONTHLY))
    FiscalYear fiscalYear = services.fiscalYearService.createFiscalYear(CompanyService.LEGACY_COMPANY_ID, '2026', LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
    Path workbook = createBasWorkbook()
    new ChartOfAccountsImportService(databaseService).importFromExcel(workbook)
    configureVatAccounts(databaseService)
    services.accountService.saveOpeningBalance(fiscalYear.id, '1510', 100.00G)

    Voucher bookedVoucher = services.voucherService.createAndBook(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 1, 15),
        'Försäljning',
        [
            new VoucherLine(null, null, 0, '1510', null, 'Kundfordran', 1000.00G, 0.00G),
            new VoucherLine(null, null, 0, '3010', null, 'Försäljning', 0.00G, 800.00G),
            new VoucherLine(null, null, 0, '2611', null, 'Utgående moms', 0.00G, 200.00G)
        ]
    )
    Voucher correctedVoucher = services.voucherService.createAndBook(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 1, 16),
        'Intern omföring',
        [
            new VoucherLine(null, null, 0, '1930', null, 'Bank', 100.00G, 0.00G),
            new VoucherLine(null, null, 0, '1510', null, 'Motkonto', 0.00G, 100.00G)
        ]
    )
    Voucher correction = services.voucherService.createCorrectionVoucher(correctedVoucher.id)
    Path attachmentFile = tempDir.resolve('bilaga.txt')
    Files.writeString(attachmentFile, 'bilaga')
    services.attachmentService.addAttachment(bookedVoucher.id, attachmentFile)

    List<ReportArchive> pdfReports = [
        ReportType.VOUCHER_LIST,
        ReportType.GENERAL_LEDGER,
        ReportType.TRIAL_BALANCE,
        ReportType.INCOME_STATEMENT,
        ReportType.BALANCE_SHEET,
        ReportType.VAT_REPORT
    ].collect { ReportType type ->
      services.journoReportService.generatePdf(new ReportSelection(type, fiscalYear.id, null, fiscalYear.startDate, fiscalYear.endDate))
    }
    assertEquals(6, pdfReports.size())
    services.reportExportService.exportCsv(new ReportSelection(ReportType.VOUCHER_LIST, fiscalYear.id, null, fiscalYear.startDate, fiscalYear.endDate))

    def vatPeriod = services.vatService.listPeriods(fiscalYear.id).first()
    services.vatService.reportPeriod(vatPeriod.id)
    services.vatService.bookTransfer(vatPeriod.id)

    Path siePath = tempDir.resolve('acceptance.sie')
    def sieResult = services.sieService.exportFiscalYear(fiscalYear.id, siePath)
    assertTrue(Files.isRegularFile(siePath))
    assertNotNull(sieResult.checksumSha256)

    lockAllAccountingPeriods(services.accountingPeriodService, fiscalYear.id)
    YearEndClosingResult closingResult = services.closingService.closeFiscalYear(fiscalYear.id)

    Path backupPath = AppPaths.backupsDirectory().resolve('acceptance-backup.zip')
    def backupResult = services.backupService.createBackup(backupPath)
    Path restoredHome = tempDir.resolve('restored-home')
    services.backupService.restoreBackup(backupPath, restoredHome)

    AcceptanceOutcome outcome = new AcceptanceOutcome(
        fiscalYearService: services.fiscalYearService,
        fiscalYear: fiscalYear,
        bookedVoucher: bookedVoucher,
        correctedVoucher: correctedVoucher,
        correction: correction,
        attachmentService: services.attachmentService,
        pdfReports: pdfReports,
        reportArchiveService: services.reportArchiveService,
        vatService: services.vatService,
        vatPeriodId: vatPeriod.id,
        closingResult: closingResult,
        backupSummary: backupResult.summary,
        restoredHome: restoredHome
    )
    assertAcceptanceOutcome(outcome)
  }

  @Test
  void acceptanceCriterionElevenCanBeDemonstrated() {
    Path verifyHome = tempDir.resolve('verify-home')
    ByteArrayOutputStream stdout = new ByteArrayOutputStream()
    PrintStream previousOut = System.out
    try {
      System.setOut(new PrintStream(stdout, true, 'UTF-8'))
      AlipsaAccounting.main(['--verify-launch', "--home=${verifyHome}"] as String[])
    } finally {
      System.setOut(previousOut)
    }

    String output = stdout.toString('UTF-8')
    Path packagingRoot = resolveRepositoryPath('packaging')
    assertTrue(output.contains('Launch verification OK'))
    assertTrue(Files.isRegularFile(verifyHome.resolve('data').resolve('accounting.mv.db')))
    assertTrue(Files.isRegularFile(packagingRoot.resolve('linux').resolve('AlipsaAccounting.png')))
    assertTrue(Files.isRegularFile(packagingRoot.resolve('windows').resolve('AlipsaAccounting.ico')))
    assertTrue(Files.isRegularFile(packagingRoot.resolve('macos').resolve('AlipsaAccounting.icns')))
  }

  private static void lockAllAccountingPeriods(AccountingPeriodService accountingPeriodService, long fiscalYearId) {
    accountingPeriodService.listPeriods(fiscalYearId).each { period ->
      accountingPeriodService.lockPeriod(period.id, 'Årsstängning')
    }
  }

  private static void configureVatAccounts(DatabaseService databaseService) {
    databaseService.withTransaction { sql ->
      sql.executeUpdate(
          'update account set vat_code = ? where account_number = ?',
          [VatCode.OUTPUT_25.name(), '3010']
      )
      insertAccountIfMissing(sql, '2611', 'Utgående moms 25%', 'LIABILITY', 'CREDIT', VatCode.OUTPUT_25.name())
      insertAccountIfMissing(sql, '2641', 'Debiterad ingående moms', 'ASSET', 'DEBIT', VatCode.INPUT_25.name())
      insertAccountIfMissing(sql, '2650', 'Redovisningskonto för moms', 'LIABILITY', 'CREDIT', null)
    }
  }

  private static void insertAccountIfMissing(
      Sql sql,
      String accountNumber,
      String accountName,
      String accountClass,
      String normalBalanceSide,
      String vatCode
  ) {
    if (sql.firstRow('select account_number from account where account_number = ?', [accountNumber]) != null) {
      return
    }
    sql.executeInsert('''
        insert into account (
            company_id,
            account_number,
            account_name,
            account_class,
            normal_balance_side,
            vat_code,
            active,
            manual_review_required,
            classification_note,
            created_at,
            updated_at
        ) values (1, ?, ?, ?, ?, ?, true, false, null, current_timestamp, current_timestamp)
    ''', [accountNumber, accountName, accountClass, normalBalanceSide, vatCode])
  }

  private static AcceptanceServices createAcceptanceServices(DatabaseService databaseService) {
    CompanySettingsService companySettingsService = new CompanySettingsService(databaseService)
    CompanyService companyService = new CompanyService(databaseService)
    AuditLogService auditLogService = new AuditLogService(databaseService)
    AccountingPeriodService accountingPeriodService = new AccountingPeriodService(databaseService, auditLogService)
    FiscalYearService fiscalYearService = new FiscalYearService(databaseService, accountingPeriodService, auditLogService)
    AttachmentService attachmentService = new AttachmentService(databaseService, auditLogService)
    VoucherService voucherService = new VoucherService(databaseService, auditLogService)
    ReportArchiveService reportArchiveService = new ReportArchiveService(databaseService)
    ReportIntegrityService reportIntegrityService = new ReportIntegrityService(voucherService, attachmentService, auditLogService)
    ReportDataService reportDataService = new ReportDataService(databaseService, fiscalYearService, accountingPeriodService)
    new AcceptanceServices(
        accountService: new AccountService(databaseService),
        companySettingsService: companySettingsService,
        accountingPeriodService: accountingPeriodService,
        fiscalYearService: fiscalYearService,
        attachmentService: attachmentService,
        voucherService: voucherService,
        reportArchiveService: reportArchiveService,
        journoReportService: new JournoReportService(
            reportDataService,
            reportArchiveService,
            reportIntegrityService,
            companyService,
            auditLogService,
            databaseService
        ),
        reportExportService: new ReportExportService(reportDataService, reportArchiveService, reportIntegrityService, auditLogService, databaseService),
        vatService: new VatService(databaseService, voucherService),
        sieService: new SieImportExportService(
            databaseService,
            accountingPeriodService,
            voucherService,
            companySettingsService,
            reportIntegrityService,
            auditLogService
        ),
        backupService: new BackupService(
            databaseService,
            attachmentService,
            reportArchiveService,
            auditLogService,
            new MigrationService(databaseService),
            reportIntegrityService
        ),
        closingService: new ClosingService(
            databaseService,
            accountingPeriodService,
            fiscalYearService,
            voucherService,
            reportIntegrityService
        )
    )
  }

  private static void assertAcceptanceOutcome(AcceptanceOutcome outcome) {
    assertTrue(outcome.fiscalYearService.findById(outcome.fiscalYear.id) != null)
    assertTrue(outcome.bookedVoucher.voucherNumber != null)
    assertEquals(outcome.correctedVoucher.id, outcome.correction.originalVoucherId)
    assertTrue(!outcome.attachmentService.listAttachments(outcome.bookedVoucher.id).isEmpty())
    assertTrue(outcome.pdfReports.every { ReportArchive archive ->
      Files.isRegularFile(outcome.reportArchiveService.resolveStoredPath(archive))
    })
    assertEquals(VatService.LOCKED, outcome.vatService.findPeriod(outcome.vatPeriodId).status)
    assertEquals('2027', outcome.closingResult.nextFiscalYear.name)
    assertTrue(Files.isRegularFile(outcome.backupSummary.backupPath))
    assertTrue(Files.exists(outcome.restoredHome.resolve('data')))
  }

  private Path createBasWorkbook() {
    Path workbookPath = tempDir.resolve('bas.xlsx')
    XSSFWorkbook workbook = new XSSFWorkbook()
    try {
      def sheet = workbook.createSheet('BAS')
      sheet.createRow(0).with {
        createCell(0).setCellValue('1510')
        createCell(1).setCellValue('Kundfordringar')
        createCell(2).setCellValue('1930')
        createCell(3).setCellValue('Företagskonto')
      }
      sheet.createRow(1).with {
        createCell(0).setCellValue('2099')
        createCell(1).setCellValue('Årets resultat')
        createCell(2).setCellValue('2610')
        createCell(3).setCellValue('Utgående moms')
      }
      sheet.createRow(2).with {
        createCell(0).setCellValue('3010')
        createCell(1).setCellValue('Försäljning')
        createCell(2).setCellValue('2650')
        createCell(3).setCellValue('Redovisningskonto för moms')
      }
      sheet.createRow(3).with {
        createCell(0).setCellValue('4010')
        createCell(1).setCellValue('Varukostnad')
      }
      Files.newOutputStream(workbookPath).withCloseable { output ->
        workbook.write(output)
      }
    } finally {
      workbook.close()
    }
    workbookPath
  }

  private static Path resolveRepositoryPath(String name) {
    Path current = Path.of(System.getProperty('user.dir')).toAbsolutePath().normalize()
    if (Files.isDirectory(current.resolve(name))) {
      return current.resolve(name)
    }
    current.parent.resolve(name)
  }

  private static final class AcceptanceServices {

    AccountService accountService
    CompanySettingsService companySettingsService
    AccountingPeriodService accountingPeriodService
    FiscalYearService fiscalYearService
    AttachmentService attachmentService
    VoucherService voucherService
    ReportArchiveService reportArchiveService
    JournoReportService journoReportService
    ReportExportService reportExportService
    VatService vatService
    SieImportExportService sieService
    BackupService backupService
    ClosingService closingService
  }

  private static final class AcceptanceOutcome {

    FiscalYearService fiscalYearService
    FiscalYear fiscalYear
    Voucher bookedVoucher
    Voucher correctedVoucher
    Voucher correction
    AttachmentService attachmentService
    List<ReportArchive> pdfReports
    ReportArchiveService reportArchiveService
    VatService vatService
    long vatPeriodId
    YearEndClosingResult closingResult
    BackupSummary backupSummary
    Path restoredHome
  }
}
