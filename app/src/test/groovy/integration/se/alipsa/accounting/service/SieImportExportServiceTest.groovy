package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue
import static org.junit.jupiter.api.Assumptions.assumeTrue

import groovy.sql.GroovyRowResult
import groovy.sql.Sql

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.AuditLogEntry
import se.alipsa.accounting.domain.Company
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.ImportJob
import se.alipsa.accounting.domain.ImportJobStatus
import se.alipsa.accounting.domain.VatPeriodicity
import se.alipsa.accounting.domain.VoucherLine
import se.alipsa.accounting.domain.report.BalanceSheetRow
import se.alipsa.accounting.domain.report.IncomeStatementRow
import se.alipsa.accounting.domain.report.ReportResult
import se.alipsa.accounting.domain.report.ReportSelection
import se.alipsa.accounting.domain.report.ReportType
import se.alipsa.accounting.service.SieImportPreview
import se.alipsa.accounting.support.AppPaths
import se.alipsa.accounting.support.I18n

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

class SieImportExportServiceTest {

  @TempDir
  Path tempDir

  private String previousHome
  private Locale previousLocale

  @BeforeEach
  void setUp() {
    previousHome = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    previousLocale = I18n.instance.locale
    I18n.instance.setLocale(Locale.forLanguageTag('sv'))
  }

  @AfterEach
  void tearDown() {
    restoreProperty(AppPaths.HOME_OVERRIDE_PROPERTY, previousHome)
    I18n.instance.setLocale(previousLocale)
  }

  @Test
  void exportedSieCanBeImportedIntoFreshTestEnvironment() {
    Path exportPath = tempDir.resolve('roundtrip.sie')
    ExportFixture fixture = createExportFixture(tempDir.resolve('source-db'), exportPath)

    switchHome(tempDir.resolve('target-db'))
    DatabaseService targetDatabaseService = DatabaseService.newForTesting()
    targetDatabaseService.initialize()
    SieImportExportService targetService = createSieService(targetDatabaseService)

    def result = targetService.importFile(CompanyService.LEGACY_COMPANY_ID, exportPath)

    assertFalse(result.duplicate)
    assertEquals(ImportJobStatus.SUCCESS, result.job.status)
    assertEquals(fixture.accountCount, result.accountsCreated)
    assertEquals(fixture.openingBalanceCount, result.openingBalanceCount)
    assertEquals(fixture.voucherCount, result.voucherCount)
    assertEquals(fixture.lineCount, result.lineCount)
    assertEquals(fixture.openingBalanceCount, countRows(targetDatabaseService, 'opening_balance'))
    assertEquals(fixture.voucherCount, countRows(targetDatabaseService, 'voucher'))
    assertEquals(fixture.accountCount, countRows(targetDatabaseService, 'account'))
  }

  @Test
  void duplicateFileHashCreatesDuplicateImportJobWithoutReimporting() {
    Path exportPath = tempDir.resolve('duplicate.sie')
    ExportFixture fixture = createExportFixture(tempDir.resolve('source-db'), exportPath)

    switchHome(tempDir.resolve('target-db'))
    DatabaseService targetDatabaseService = DatabaseService.newForTesting()
    targetDatabaseService.initialize()
    SieImportExportService targetService = createSieService(targetDatabaseService)

    def firstImport = targetService.importFile(CompanyService.LEGACY_COMPANY_ID, exportPath)
    def secondImport = targetService.importFile(CompanyService.LEGACY_COMPANY_ID, exportPath)
    List<ImportJob> jobs = targetService.listImportJobs(CompanyService.LEGACY_COMPANY_ID, 10)

    assertEquals(ImportJobStatus.SUCCESS, firstImport.job.status)
    assertTrue(secondImport.duplicate)
    assertEquals(ImportJobStatus.DUPLICATE, secondImport.job.status)
    assertEquals(fixture.voucherCount, countRows(targetDatabaseService, 'voucher'))
    assertEquals(2, jobs.size())
    assertEquals(ImportJobStatus.DUPLICATE, jobs.first().status)
    assertEquals(ImportJobStatus.SUCCESS, jobs.last().status)
  }

  @Test
  void malformedSieFileIsRejectedAndRecordedAsFailedJob() {
    switchHome(tempDir.resolve('malformed-db'))
    DatabaseService databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    SieImportExportService service = createSieService(databaseService)
    Path malformed = tempDir.resolve('malformed.sie')
    malformed.toFile().text = 'this is not a sie file'

    IllegalArgumentException exception = assertThrows(IllegalArgumentException) {
      service.importFile(CompanyService.LEGACY_COMPANY_ID, malformed)
    }

    assertTrue(exception.message.contains('SIE'))
    assertEquals(ImportJobStatus.FAILED, service.listImportJobs(CompanyService.LEGACY_COMPANY_ID, 1).first().status)
  }

  @Test
  void oversizedSieFileIsRejectedBeforeReadingContent() {
    switchHome(tempDir.resolve('oversized-db'))
    DatabaseService databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    SieImportExportService service = createSieService(databaseService)
    Path oversized = tempDir.resolve('oversized.sie')
    oversized.toFile().createNewFile()
    new RandomAccessFile(oversized.toFile(), 'rw').withCloseable { RandomAccessFile file ->
      file.setLength(50L * 1024L * 1024L + 1L)
    }

    IllegalArgumentException exception = assertThrows(IllegalArgumentException) {
      service.importFile(CompanyService.LEGACY_COMPANY_ID, oversized)
    }

    assertTrue(exception.message.contains('Max 50 MB'))
    assertTrue(service.listImportJobs(CompanyService.LEGACY_COMPANY_ID).isEmpty())
  }

  @Test
  void importIntoClosedFiscalYearIsRejectedAndRecordedAsFailedJob() {
    Path exportPath = tempDir.resolve('closed-year.sie')
    createExportFixture(tempDir.resolve('source-db'), exportPath)

    switchHome(tempDir.resolve('locked-target-db'))
    DatabaseService targetDatabaseService = DatabaseService.newForTesting()
    targetDatabaseService.initialize()
    AuditLogService auditLogService = new AuditLogService(targetDatabaseService)
    AccountingPeriodService accountingPeriodService = new AccountingPeriodService(targetDatabaseService, auditLogService)
    FiscalYearService fiscalYearService = new FiscalYearService(targetDatabaseService, accountingPeriodService, auditLogService)
    FiscalYear fiscalYear = fiscalYearService.createFiscalYear(CompanyService.LEGACY_COMPANY_ID, '2026', LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
    fiscalYearService.closeFiscalYear(fiscalYear.id)
    SieImportExportService targetService = createSieService(targetDatabaseService)

    IllegalStateException exception = assertThrows(IllegalStateException) {
      targetService.importFile(CompanyService.LEGACY_COMPANY_ID, exportPath)
    }

    assertTrue(exception.message.contains('stängt'))
    assertEquals(ImportJobStatus.FAILED, targetService.listImportJobs(CompanyService.LEGACY_COMPANY_ID, 1).first().status)
  }

  @Test
  void replaceFiscalYearRemovesExistingYearContentBeforeImport() {
    Path exportPath = tempDir.resolve('replace-year.sie')
    ExportFixture fixture = createExportFixture(tempDir.resolve('source-db'), exportPath)

    switchHome(tempDir.resolve('replace-target-db'))
    DatabaseService targetDatabaseService = DatabaseService.newForTesting()
    targetDatabaseService.initialize()
    seedReplaceTargetEnvironment(targetDatabaseService)
    AuditLogService auditLogService = new AuditLogService(targetDatabaseService)
    AuditLogEntry unrelatedEntry = auditLogService.logImport(
        'Orelaterad audithändelse',
        'ska vara kedjeankare efter arkivering',
        CompanyService.LEGACY_COMPANY_ID
    )
    SieImportExportService targetService = createSieService(targetDatabaseService)

    SieImportResult result = targetService.replaceFiscalYear(CompanyService.LEGACY_COMPANY_ID, exportPath)

    assertFalse(result.duplicate)
    assertEquals(ImportJobStatus.SUCCESS, result.job.status)
    assertTrue(result.job.summary.contains('Ersättningsimport klar'))
    assertEquals(fixture.openingBalanceCount, countRows(targetDatabaseService, 'opening_balance'))
    assertEquals(fixture.voucherCount, countRows(targetDatabaseService, 'voucher'))
    assertEquals(0, countRows(targetDatabaseService, 'attachment'))
    assertEquals(0, countRows(targetDatabaseService, 'report_archive'))
    assertEquals(12, countRows(targetDatabaseService, 'vat_period'))
    assertFalse(Files.exists(AppPaths.reportsDirectory().resolve('report-archive/old-report.pdf')))
    targetDatabaseService.withSql { Sql sql ->
      GroovyRowResult replacedVoucher = sql.firstRow(
          'select count(*) as total from voucher where description = ?',
          ['Lokal testverifikation']
      ) as GroovyRowResult
      assertEquals(0, ((Number) replacedVoucher.get('total')).intValue())

      GroovyRowResult firstReplacementAuditRow = sql.firstRow('''
          select previous_hash as previousHash
            from audit_log
           where company_id = ?
             and archived = false
             and id > ?
           order by id asc
           limit 1
      ''', [CompanyService.LEGACY_COMPANY_ID, unrelatedEntry.id]) as GroovyRowResult
      GroovyRowResult latestLiveEntry = sql.firstRow('''
          select entry_hash as entryHash
            from audit_log
           where company_id = ?
             and archived = false
           order by id desc
           limit 1
      ''', [CompanyService.LEGACY_COMPANY_ID]) as GroovyRowResult
      GroovyRowResult chainHead = sql.firstRow('''
          select last_entry_hash as lastEntryHash
            from audit_log_chain_head
           where company_id = ?
      ''', [CompanyService.LEGACY_COMPANY_ID]) as GroovyRowResult
      assertNotNull(firstReplacementAuditRow, 'Replacement import should create live audit entries')
      assertNotNull(chainHead, 'Chain head row must exist')
      assertEquals(
          unrelatedEntry.entryHash,
          firstReplacementAuditRow?.get('previousHash') as String,
          'The first live audit row after replacement must chain from the latest surviving live audit entry'
      )
      assertEquals(
          latestLiveEntry?.get('entryHash') as String,
          chainHead?.get('lastEntryHash') as String,
          'Chain head must reference the latest live audit log entry after replacement'
      )
    }
  }

  @Test
  void replaceFiscalYearWithClosingEntriesIsRejectedAndRecordedAsFailedJob() {
    Path exportPath = tempDir.resolve('replace-with-closing.sie')
    createExportFixture(tempDir.resolve('replace-with-closing-source-db'), exportPath)

    switchHome(tempDir.resolve('replace-with-closing-target-db'))
    DatabaseService targetDatabaseService = DatabaseService.newForTesting()
    targetDatabaseService.initialize()
    seedReplaceTargetEnvironment(targetDatabaseService)
    long fiscalYearId = findFiscalYearId(targetDatabaseService, '2026')
    insertClosingEntry(targetDatabaseService, fiscalYearId)
    SieImportExportService targetService = createSieService(targetDatabaseService)

    IllegalStateException exception = assertThrows(IllegalStateException) {
      targetService.replaceFiscalYear(CompanyService.LEGACY_COMPANY_ID, exportPath)
    }

    assertTrue(exception.message.contains('bokslutsposter'))
    assertEquals(ImportJobStatus.FAILED, targetService.listImportJobs(CompanyService.LEGACY_COMPANY_ID, 1).first().status)
    assertTrue(countRows(targetDatabaseService, 'voucher') > 0, 'Existing fiscal-year content must remain untouched on failure')
  }

  @Test
  void replaceFiscalYearRemainsSuccessfulWhenStoredFileCleanupFails() {
    Path exportPath = tempDir.resolve('replace-cleanup-failure.sie')
    createExportFixture(tempDir.resolve('replace-cleanup-failure-source-db'), exportPath)

    switchHome(tempDir.resolve('replace-cleanup-failure-target-db'))
    DatabaseService targetDatabaseService = DatabaseService.newForTesting()
    targetDatabaseService.initialize()
    seedReplaceTargetEnvironment(targetDatabaseService)
    Path stubbornDirectory = configureUndeletableReportArchivePath(targetDatabaseService)
    SieImportExportService targetService = createSieService(targetDatabaseService)

    SieImportResult result = targetService.replaceFiscalYear(CompanyService.LEGACY_COMPANY_ID, exportPath)

    assertEquals(ImportJobStatus.SUCCESS, result.job.status)
    assertEquals(ImportJobStatus.SUCCESS, targetService.listImportJobs(CompanyService.LEGACY_COMPANY_ID, 1).first().status)
    assertTrue(Files.isDirectory(stubbornDirectory), 'Failed cleanup should be logged without changing job status')
  }

  @Test
  void importIntoFiscalYearWithExistingVatPeriodsSucceeds() {
    switchHome(tempDir.resolve('idempotent-vat-db'))
    DatabaseService databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    AuditLogService auditLogService = new AuditLogService(databaseService)
    AccountingPeriodService accountingPeriodService = new AccountingPeriodService(databaseService, auditLogService)
    FiscalYearService fiscalYearService = new FiscalYearService(databaseService, accountingPeriodService, auditLogService)
    // createFiscalYear already calls VatService.ensurePeriodsForFiscalYear internally
    fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID, '2026', LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)
    )
    int vatPeriodsAfterCreate = countRows(databaseService, 'vat_period')
    assertTrue(vatPeriodsAfterCreate > 0, 'VAT periods should have been created with the fiscal year')

    SieImportExportService service = createSieService(databaseService)
    // Import into the same fiscal year (no vouchers or opening balances yet, so import is allowed)
    service.importFile(CompanyService.LEGACY_COMPANY_ID,
        writeSimpleSie(tempDir.resolve('idempotent-vat.sie'), '1510', 'Kundfordringar'))

    assertEquals(vatPeriodsAfterCreate, countRows(databaseService, 'vat_period'),
        'ensurePeriodsForFiscalYear must be idempotent: period count must not change on re-import')
  }

  @Test
  void importClassifiesGroup21AccountsAsLiabilities() {
    switchHome(tempDir.resolve('group21-db'))
    DatabaseService databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    SieImportExportService service = createSieService(databaseService)
    Path filePath = writeSimpleSie(tempDir.resolve('group21.sie'), '2120', 'Periodiseringsfonder')

    service.importFile(CompanyService.LEGACY_COMPANY_ID, filePath)

    databaseService.withSql { Sql sql ->
      GroovyRowResult row = sql.firstRow(
          'select account_class as accountClass, manual_review_required as manualReviewRequired from account where account_number = ?',
          ['2120']
      ) as GroovyRowResult
      assertEquals('LIABILITY', row.get('accountClass'))
      assertEquals(false, row.get('manualReviewRequired'))
    }
  }

  @Test
  void importPreservesTrustedExistingAccountClassification() {
    switchHome(tempDir.resolve('preserve-db'))
    DatabaseService databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    databaseService.withTransaction { Sql sql ->
      insertAccount(sql, '2120', 'Egen klassning', 'EQUITY', 'CREDIT')
    }
    SieImportExportService service = createSieService(databaseService)
    Path filePath = writeSimpleSie(tempDir.resolve('preserve.sie'), '2120', 'Periodiseringsfonder')

    service.importFile(CompanyService.LEGACY_COMPANY_ID, filePath)

    databaseService.withSql { Sql sql ->
      GroovyRowResult row = sql.firstRow('''
          select account_name as accountName,
                 account_class as accountClass,
                 normal_balance_side as normalBalanceSide,
                 manual_review_required as manualReviewRequired
            from account
           where account_number = ?
      ''', ['2120']) as GroovyRowResult
      assertEquals('Periodiseringsfonder', row.get('accountName'))
      assertEquals('EQUITY', row.get('accountClass'))
      assertEquals('CREDIT', row.get('normalBalanceSide'))
      assertEquals(false, row.get('manualReviewRequired'))
    }
  }

  @Test
  void realSieFileCanBeImportedSuccessfully() {
    URL resource = getClass().getResource('/sie/Test_HB-2025.SE')
    URL balanceResource = getClass().getResource('/report/Test_HB_Balansrapport Bokslut 202512.xlsx')
    URL incomeResource = getClass().getResource('/report/Test_HB_Resultatrapport Bokslut 202512.xlsx')
    assumeTrue(resource != null, 'Test SIE file not present, skipping')
    assumeTrue(balanceResource != null, 'Balance sheet fixture not present, skipping')
    assumeTrue(incomeResource != null, 'Income statement fixture not present, skipping')
    Path sieFile = Path.of(resource.toURI())

    switchHome(tempDir.resolve('real-import-db'))
    DatabaseService databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    CompanyService companyService = new CompanyService(databaseService)
    companyService.save(new Company(
        CompanyService.LEGACY_COMPANY_ID, 'Test HB', '998877-9876', 'SEK', 'sv-SE',
        VatPeriodicity.MONTHLY, true, null, null
    ))
    SieImportExportService service = createSieService(databaseService)

    alipsa.sieparser.SieCompany peeked = service.peekSieCompany(sieFile)
    assertEquals('Test HB', peeked.name)
    assertEquals('998877-9876', peeked.orgIdentifier)

    SieImportResult result = service.importFile(CompanyService.LEGACY_COMPANY_ID, sieFile)

    assertFalse(result.duplicate)
    assertEquals(ImportJobStatus.SUCCESS, result.job.status)
    assertNotNull(result.fiscalYear)
    assertEquals(145, result.voucherCount)
    assertTrue(result.accountsCreated > 0)
    assertTrue(result.job.errorLog == null || result.job.errorLog.isEmpty(),
        "Unexpected warnings in import: ${result.job.errorLog}")

    ReportDataService reportDataService = createReportDataService(databaseService)
    ReportSelection fullYearSelection = new ReportSelection(
        ReportType.BALANCE_SHEET,
        result.fiscalYear.id,
        null,
        result.fiscalYear.startDate,
        result.fiscalYear.endDate
    )
    ReportResult balanceReport = reportDataService.generate(fullYearSelection)
    ReportResult incomeReport = reportDataService.generate(new ReportSelection(
        ReportType.INCOME_STATEMENT,
        result.fiscalYear.id,
        null,
        result.fiscalYear.startDate,
        result.fiscalYear.endDate
    ))

    Map<String, BigDecimal> expectedBalance = expectedBalanceFixtureAmounts()
    Map<String, BigDecimal> actualBalance = balanceAmounts(balanceReport)
    Map<String, BigDecimal> expectedIncome = expectedIncomeFixtureAmounts()
    Map<String, BigDecimal> actualIncome = incomeAmounts(incomeReport)

    assertEquals(expectedBalance['1630'], actualBalance['1630'])
    assertEquals(expectedBalance['1910'], actualBalance['1910'])
    assertEquals(expectedBalance['1920'], actualBalance['1920'])
    assertEquals(expectedBalance['1925'], actualBalance['1925'])
    assertNull(actualBalance['1940'])
    assertEquals(expectedBalance['Summa fordringar'], actualBalance['Övriga kortfristiga fordringar'])
    assertEquals(expectedBalance['Summa kassa och bank'], actualBalance['Kassa och bank'])
    assertEquals(expectedBalance['Summa omsättningstillgångar'], actualBalance['Omsättningstillgångar'])
    assertEquals(expectedBalance['2010'], actualBalance['2010'])
    assertEquals(expectedBalance['2011'], actualBalance['2011'])
    assertEquals(expectedBalance['Summa eget kapital'], actualBalance['Eget kapital'])
    assertEquals(expectedBalance['2650'], actualBalance['2650'])
    assertEquals(expectedBalance['Summa kortfristiga skulder'], actualBalance['Kortfristiga skulder'])
    assertEquals(expectedBalance['SUMMA TILLGÅNGAR'], balanceReport.templateModel.assetTotal)
    assertEquals(expectedBalance['SUMMA EGET OCH FRÄMMANDE KAPITAL'], balanceReport.templateModel.equityAndLiabilitiesTotal)

    assertEquals(expectedIncome['3043'], actualIncome['3043'])
    assertEquals(expectedIncome['3305'], actualIncome['3305'])
    assertEquals(expectedIncome['3740'], actualIncome['3740'])
    assertEquals(expectedIncome['4515'], actualIncome['4515'])
    assertEquals(expectedIncome['4531'], actualIncome['4531'])
    assertEquals(expectedIncome['4535'], actualIncome['4535'])
    assertEquals(expectedIncome['4545'], actualIncome['4545'])
    assertEquals(expectedIncome['4599'], actualIncome['4599'])
    assertEquals(expectedIncome['5420'], actualIncome['5420'])
    assertEquals(expectedIncome['5915'], actualIncome['5915'])
    assertEquals(expectedIncome['6540'], actualIncome['6540'])
    assertEquals(expectedIncome['8311'], actualIncome['8311'])
    assertEquals(expectedIncome['8314'], actualIncome['8314'])
    assertEquals(expectedIncome['8423'], actualIncome['8423'])
    assertEquals(expectedIncome['SUMMA RÖRELSEINTÄKTER'], actualIncome['SUMMA RÖRELSEINTÄKTER'])
    assertEquals(expectedIncome['SUMMA RÖRELSEKOSTNADER'], actualIncome['SUMMA RÖRELSEKOSTNADER'])
    assertEquals(expectedIncome['Rörelseresultat'], actualIncome['Rörelseresultat'])
    assertEquals(expectedIncome['Summa finansiella poster'], actualIncome['Summa finansiella poster'])
    assertEquals(expectedIncome['Resultat efter finansiella poster'], actualIncome['Resultat efter finansiella poster'])
    assertEquals(expectedIncome['Resultat före skatt'], actualIncome['Resultat före skatt'])
    assertEquals(expectedIncome['ÅRETS RESULTAT'], actualIncome['ÅRETS RESULTAT'])
    assertEquals(expectedIncome['ÅRETS RESULTAT'], incomeReport.templateModel.result)
  }

  @Test
  void peekSieCompanyReturnsCompanyNameAndOrgNumber() {
    switchHome(tempDir.resolve('peek-db'))
    DatabaseService databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    SieImportExportService service = createSieService(databaseService)
    Path sieFile = writeSimpleSie(tempDir.resolve('peek.sie'), '1000', 'Kassa')

    alipsa.sieparser.SieCompany company = service.peekSieCompany(sieFile)

    assertEquals('Testbolaget AB', company.name)
    assertEquals('556677-8899', company.orgIdentifier)
  }

  @Test
  void peekSieCompanyReturnsNullForFileWithNoCompanyInfo() {
    switchHome(tempDir.resolve('peek-empty-db'))
    DatabaseService databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    SieImportExportService service = createSieService(databaseService)
    Path sieFile = tempDir.resolve('nocompany.sie')
    sieFile.toFile().text = '#FLAGGA 0\n#SIETYP 4\n#RAR 0 20260101 20261231\n'

    alipsa.sieparser.SieCompany company = service.peekSieCompany(sieFile)

    // SieDocument may initialise fnamn to an empty object or null depending on the parser
    assertTrue(company == null || (company.name == null && company.orgIdentifier == null))
  }

  @Test
  void priorYearOpeningBalancesAreNotImported() {
    switchHome(tempDir.resolve('yearNr-filter-db'))
    DatabaseService databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    CompanyService companyService = new CompanyService(databaseService)
    companyService.save(new Company(
        CompanyService.LEGACY_COMPANY_ID, 'Testbolaget AB', '556677-8899', 'SEK', 'sv-SE',
        VatPeriodicity.MONTHLY, true, null, null
    ))
    SieImportExportService service = createSieService(databaseService)

    Path sieFile = tempDir.resolve('multi-year.sie')
    sieFile.toFile().text = """#FLAGGA 0
#PROGRAM "Test" "1.0"
#FORMAT PC8
#GEN 20260101 "tester"
#SIETYP 4
#FNAMN "Testbolaget AB"
#ORGNR 556677-8899
#RAR 0 20260101 20261231
#RAR -1 20250101 20251231
#KONTO 1920 "Bankgiro"
#IB 0 1920 500.00
#IB -1 1920 200.00
"""

    SieImportResult result = service.importFile(CompanyService.LEGACY_COMPANY_ID, sieFile)

    assertEquals(ImportJobStatus.SUCCESS, result.job.status)
    databaseService.withSql { Sql sql ->
      GroovyRowResult row = sql.firstRow('''
          select ob.amount from opening_balance ob
            join account a on a.id = ob.account_id
           where ob.fiscal_year_id = ? and a.account_number = '1920'
      ''', [result.fiscalYear.id])
      assertNotNull(row, 'Opening balance row should exist for 1920')
      assertEquals(500.00G, (BigDecimal) row.get('amount'))
    }
  }

  @Test
  void previewWithReplaceDetectsClosingEntries() {
    Path exportPath = tempDir.resolve('closing-preview.sie')
    createExportFixture(tempDir.resolve('closing-preview-source-db'), exportPath)

    switchHome(tempDir.resolve('closing-preview-target-db'))
    DatabaseService targetDatabaseService = DatabaseService.newForTesting()
    targetDatabaseService.initialize()
    seedReplaceTargetEnvironment(targetDatabaseService)
    long fiscalYearId = findFiscalYearId(targetDatabaseService, '2026')
    insertClosingEntry(targetDatabaseService, fiscalYearId)
    SieImportExportService service = createSieService(targetDatabaseService)

    SieImportPreview preview = service.previewSieImport(CompanyService.LEGACY_COMPANY_ID, exportPath, true)

    assertTrue(
        preview.blockingIssues.any { String issue -> issue.contains('bokslutsposter') },
        "Preview should report closing entries as a blocking issue for replace, got: ${preview.blockingIssues}"
    )
  }

  @Test
  void reopenAndReplaceFiscalYearSucceeds() {
    Path exportPath = tempDir.resolve('reopen-replace.sie')
    ExportFixture fixture = createExportFixture(tempDir.resolve('reopen-replace-source-db'), exportPath)

    switchHome(tempDir.resolve('reopen-replace-target-db'))
    DatabaseService targetDatabaseService = DatabaseService.newForTesting()
    targetDatabaseService.initialize()
    seedReplaceTargetEnvironment(targetDatabaseService)
    AuditLogService auditLogService = new AuditLogService(targetDatabaseService)
    AccountingPeriodService accountingPeriodService = new AccountingPeriodService(targetDatabaseService, auditLogService)
    FiscalYearService fiscalYearService = new FiscalYearService(targetDatabaseService, accountingPeriodService, auditLogService)
    long fiscalYearId = findFiscalYearId(targetDatabaseService, '2026')
    fiscalYearService.closeFiscalYear(fiscalYearId)
    assertTrue(fiscalYearService.findById(fiscalYearId).closed, 'Year should be closed before test')
    SieImportExportService service = createSieService(targetDatabaseService)

    SieImportResult result = service.reopenAndReplaceFiscalYear(CompanyService.LEGACY_COMPANY_ID, exportPath)

    assertFalse(result.duplicate)
    assertEquals(ImportJobStatus.SUCCESS, result.job.status)
    assertTrue(result.job.summary.contains('Ersättningsimport klar'))
    assertFalse(fiscalYearService.findById(fiscalYearId).closed, 'Year should be open after reopen-and-replace')
    assertEquals(fixture.voucherCount, countRows(targetDatabaseService, 'voucher'))
    assertEquals(fixture.openingBalanceCount, countRows(targetDatabaseService, 'opening_balance'))
  }

  @Test
  void reopenAndReplaceFiscalYearFailsIfClosingEntriesExist() {
    Path exportPath = tempDir.resolve('reopen-closing-fail.sie')
    createExportFixture(tempDir.resolve('reopen-closing-fail-source-db'), exportPath)

    switchHome(tempDir.resolve('reopen-closing-fail-target-db'))
    DatabaseService targetDatabaseService = DatabaseService.newForTesting()
    targetDatabaseService.initialize()
    seedReplaceTargetEnvironment(targetDatabaseService)
    AuditLogService auditLogService = new AuditLogService(targetDatabaseService)
    AccountingPeriodService accountingPeriodService = new AccountingPeriodService(targetDatabaseService, auditLogService)
    FiscalYearService fiscalYearService = new FiscalYearService(targetDatabaseService, accountingPeriodService, auditLogService)
    long fiscalYearId = findFiscalYearId(targetDatabaseService, '2026')
    insertClosingEntry(targetDatabaseService, fiscalYearId)
    fiscalYearService.closeFiscalYear(fiscalYearId)
    int vouchersBefore = countRows(targetDatabaseService, 'voucher')
    SieImportExportService service = createSieService(targetDatabaseService)

    IllegalStateException exception = assertThrows(IllegalStateException) {
      service.reopenAndReplaceFiscalYear(CompanyService.LEGACY_COMPANY_ID, exportPath)
    }

    assertTrue(exception.message.contains('bokslutsposter'))
    assertTrue(fiscalYearService.findById(fiscalYearId).closed, 'Year must remain closed after failed reopen-and-replace')
    assertEquals(vouchersBefore, countRows(targetDatabaseService, 'voucher'), 'Existing content must remain untouched')
  }

  private ExportFixture createExportFixture(Path home, Path exportPath) {
    switchHome(home)
    DatabaseService databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    SeededServices services = seedEnvironment(databaseService)
    services.sieService.exportFiscalYear(services.fiscalYear.id, exportPath)
    new ExportFixture(services.accountCount, services.openingBalanceCount, services.voucherCount, services.lineCount)
  }

  private SeededServices seedEnvironment(DatabaseService databaseService) {
    AuditLogService auditLogService = new AuditLogService(databaseService)
    AccountingPeriodService accountingPeriodService = new AccountingPeriodService(databaseService, auditLogService)
    FiscalYearService fiscalYearService = new FiscalYearService(databaseService, accountingPeriodService, auditLogService)
    VoucherService voucherService = new VoucherService(databaseService, auditLogService)
    CompanyService companyService = new CompanyService(databaseService)
    companyService.save(new Company(
        CompanyService.LEGACY_COMPANY_ID, 'Testbolaget AB', '556677-8899', 'SEK', 'sv-SE',
        VatPeriodicity.MONTHLY, true, null, null
    ))
    FiscalYear fiscalYear = fiscalYearService.createFiscalYear(CompanyService.LEGACY_COMPANY_ID, '2026', LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))

    databaseService.withTransaction { Sql sql ->
      insertAccount(sql, '2010', 'Eget kapital', 'EQUITY', 'CREDIT')
      insertAccount(sql, '1510', 'Kundfordringar', 'ASSET', 'DEBIT')
      insertAccount(sql, '2611', 'Utgående moms 25%', 'LIABILITY', 'CREDIT')
      insertAccount(sql, '3010', 'Försäljning', 'INCOME', 'CREDIT')
      sql.executeInsert('''
          insert into opening_balance (
              fiscal_year_id,
              account_id,
              amount,
              created_at,
              updated_at
          ) values (?, (select id from account where account_number = ?), ?, current_timestamp, current_timestamp)
      ''', [fiscalYear.id, '1510', 100.00G])
      sql.executeInsert('''
          insert into opening_balance (
              fiscal_year_id,
              account_id,
              amount,
              created_at,
              updated_at
          ) values (?, (select id from account where account_number = ?), ?, current_timestamp, current_timestamp)
      ''', [fiscalYear.id, '2010', 100.00G])
    }

    voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 1, 15),
        'Försäljning januari',
        [
            new VoucherLine(null, null, 0, null, '1510', null, 'Kundfordran', 1250.00G, 0.00G),
            new VoucherLine(null, null, 0, null, '3010', null, 'Försäljning', 0.00G, 1000.00G),
            new VoucherLine(null, null, 0, null, '2611', null, 'Utgående moms', 0.00G, 250.00G)
        ]
    )

    new SeededServices(
        createSieService(databaseService),
        fiscalYear,
        4,
        2,
        1,
        3
    )
  }

  private void seedReplaceTargetEnvironment(DatabaseService databaseService) {
    AuditLogService auditLogService = new AuditLogService(databaseService)
    AccountingPeriodService accountingPeriodService = new AccountingPeriodService(databaseService, auditLogService)
    FiscalYearService fiscalYearService = new FiscalYearService(databaseService, accountingPeriodService, auditLogService)
    VoucherService voucherService = new VoucherService(databaseService, auditLogService)
    AttachmentService attachmentService = new AttachmentService(databaseService, auditLogService)
    CompanyService companyService = new CompanyService(databaseService)
    companyService.save(new Company(
        CompanyService.LEGACY_COMPANY_ID, 'Testbolaget AB', '556677-8899', 'SEK', 'sv-SE',
        VatPeriodicity.MONTHLY, true, null, null
    ))
    FiscalYear fiscalYear = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID,
        '2026',
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 12, 31)
    )

    databaseService.withTransaction { Sql sql ->
      insertAccount(sql, '1510', 'Kundfordringar', 'ASSET', 'DEBIT')
      insertAccount(sql, '1930', 'Bank', 'ASSET', 'DEBIT')
      insertAccount(sql, '2010', 'Eget kapital', 'EQUITY', 'CREDIT')
      insertAccount(sql, '3010', 'Försäljning', 'INCOME', 'CREDIT')
      sql.executeInsert('''
          insert into opening_balance (
              fiscal_year_id,
              account_id,
              amount,
              created_at,
              updated_at
          ) values (?, (select id from account where account_number = ?), ?, current_timestamp, current_timestamp)
      ''', [fiscalYear.id, '1510', 999.00G])
      sql.executeInsert('''
          insert into report_archive (
              report_type,
              report_format,
              fiscal_year_id,
              accounting_period_id,
              start_date,
              end_date,
              file_name,
              storage_path,
              checksum_sha256,
              parameters,
              created_at
          ) values ('VOUCHER_LIST', 'PDF', ?, null, ?, ?, ?, ?, ?, ?, current_timestamp)
      ''', [
          fiscalYear.id,
          java.sql.Date.valueOf(fiscalYear.startDate),
          java.sql.Date.valueOf(fiscalYear.endDate),
          'old-report.pdf',
          'report-archive/old-report.pdf',
          'abc123',
          '{}'
      ])
    }
    Path archivedReportPath = AppPaths.reportsDirectory().resolve('report-archive/old-report.pdf')
    Files.createDirectories(archivedReportPath.parent)
    Files.writeString(archivedReportPath, 'gammal rapport')

    def voucher = voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 1, 5),
        'Lokal testverifikation',
        [
            new VoucherLine(null, null, 0, null, '1510', null, 'Kundfordran', 125.00G, 0.00G),
            new VoucherLine(null, null, 0, null, '3010', null, 'Försäljning', 0.00G, 125.00G)
        ]
    )
    Path attachmentFile = tempDir.resolve('replace-attachment.txt')
    attachmentFile.toFile().text = 'lokal bilaga'
    attachmentService.addAttachment(voucher.id, attachmentFile)
  }

  private static long findFiscalYearId(DatabaseService databaseService, String fiscalYearName) {
    databaseService.withSql { Sql sql ->
      GroovyRowResult row = sql.firstRow(
          'select id from fiscal_year where name = ?',
          [fiscalYearName]
      ) as GroovyRowResult
      ((Number) row.get('id')).longValue()
    }
  }

  private static void insertClosingEntry(DatabaseService databaseService, long fiscalYearId) {
    databaseService.withTransaction { Sql sql ->
      GroovyRowResult accountRow = sql.firstRow(
          'select id from account where account_number = ?',
          ['3010']
      ) as GroovyRowResult
      long accountId = ((Number) accountRow.get('id')).longValue()
      sql.executeInsert('''
          insert into closing_entry (fiscal_year_id, entry_type, account_id, amount, created_at)
          values (?, 'RESULT_CLOSING', ?, 1000.00, current_timestamp)
      ''', [fiscalYearId, accountId])
    }
  }

  private Path configureUndeletableReportArchivePath(DatabaseService databaseService) {
    Path stubbornDirectory = AppPaths.reportsDirectory().resolve('report-archive/stubborn-dir')
    Files.createDirectories(stubbornDirectory)
    Files.writeString(stubbornDirectory.resolve('child.txt'), 'keep me')
    databaseService.withTransaction { Sql sql ->
      sql.executeUpdate('''
          update report_archive
             set storage_path = ?
           where file_name = ?
      ''', ['report-archive/stubborn-dir', 'old-report.pdf'])
    }
    stubbornDirectory
  }

  private SieImportExportService createSieService(DatabaseService databaseService) {
    AuditLogService auditLogService = new AuditLogService(databaseService)
    AccountingPeriodService accountingPeriodService = new AccountingPeriodService(databaseService, auditLogService)
    VoucherService voucherService = new VoucherService(databaseService, auditLogService)
    ReportIntegrityService reportIntegrityService = new ReportIntegrityService(
        new AttachmentService(databaseService, auditLogService),
        auditLogService
    )
    new SieImportExportService(
        databaseService,
        accountingPeriodService,
        voucherService,
        new CompanyService(databaseService),
        reportIntegrityService,
        auditLogService,
        new FiscalYearService(databaseService)
    )
  }

  private ReportDataService createReportDataService(DatabaseService databaseService) {
    AuditLogService auditLogService = new AuditLogService(databaseService)
    AccountingPeriodService accountingPeriodService = new AccountingPeriodService(databaseService, auditLogService)
    FiscalYearService fiscalYearService = new FiscalYearService(databaseService, accountingPeriodService, auditLogService)
    new ReportDataService(databaseService, fiscalYearService, accountingPeriodService)
  }

  private static Map<String, BigDecimal> balanceAmounts(ReportResult report) {
    List<BalanceSheetRow> rows = report.templateModel.typedRows as List<BalanceSheetRow>
    rows.findAll { BalanceSheetRow row -> row.amount != null }.collectEntries { BalanceSheetRow row ->
      String label = row.accountNumber ?: row.subgroupDisplayName
      [(label): row.amount]
    } as Map<String, BigDecimal>
  }

  private static Map<String, BigDecimal> incomeAmounts(ReportResult report) {
    List<IncomeStatementRow> rows = report.templateModel.typedRows as List<IncomeStatementRow>
    rows.findAll { IncomeStatementRow row -> row.amount != null }.collectEntries { IncomeStatementRow row ->
      String label = row.displayLabel ==~ /^\d+ .+/ ? row.displayLabel.split(' ', 2)[0] : row.displayLabel
      [(label): row.amount]
    } as Map<String, BigDecimal>
  }

  private static Map<String, BigDecimal> expectedBalanceFixtureAmounts() {
    [
        '1630'                           : 620.00G,
        '1910'                           : 10.00G,
        '1920'                           : 10973.10G,
        '1925'                           : 1020.64G,
        '1940'                           : 0.00G,
        'Summa fordringar'               : 620.00G,
        'Summa kassa och bank'           : 12003.74G,
        'Summa omsättningstillgångar'    : 12623.74G,
        '2010'                           : -226687.16G,
        '2011'                           : 208740.42G,
        'Summa eget kapital'             : -17946.74G,
        '2650'                           : 5323.00G,
        'Summa kortfristiga skulder'     : 5323.00G,
        'SUMMA TILLGÅNGAR'               : 12623.74G,
        'SUMMA EGET OCH FRÄMMANDE KAPITAL': -12623.74G
    ]
  }

  private static Map<String, BigDecimal> expectedIncomeFixtureAmounts() {
    [
        '3043'                          : 4905.66G,
        '3305'                          : 446662.45G,
        '3740'                          : 1.49G,
        '4515'                          : -9064.02G,
        '4531'                          : -45145.91G,
        '4535'                          : -577.53G,
        '4545'                          : -4131.79G,
        '4599'                          : 58919.25G,
        '5420'                          : -15139.72G,
        '5915'                          : -4068.00G,
        '6540'                          : -48630.41G,
        '8311'                          : 627.37G,
        '8314'                          : 73.00G,
        '8423'                          : -119.00G,
        'SUMMA RÖRELSEINTÄKTER'         : 451569.60G,
        'SUMMA RÖRELSEKOSTNADER'        : -90146.32G,
        'Rörelseresultat'               : 361423.28G,
        'Summa finansiella poster'      : 581.37G,
        'Resultat efter finansiella poster': 362004.65G,
        'Resultat före skatt'           : 362004.65G,
        'ÅRETS RESULTAT'                : 362004.65G
    ]
  }

  private Path writeSimpleSie(Path filePath, String accountNumber, String accountName) {
    filePath.toFile().text = """#FLAGGA 0
#PROGRAM "Test" "1.0"
#FORMAT PC8
#GEN 20260101 "tester"
#SIETYP 4
#FNAMN "Testbolaget AB"
#ORGNR 556677-8899
#RAR 0 20260101 20261231
#KONTO ${accountNumber} "${accountName}"
#IB 0 ${accountNumber} 100.00
"""
    filePath
  }

  private static int countRows(DatabaseService databaseService, String tableName) {
    databaseService.withSql { Sql sql ->
      GroovyRowResult row = sql.firstRow('select count(*) as total from ' + tableName) as GroovyRowResult
      ((Number) row.get('total')).intValue()
    }
  }

  private static void insertAccount(Sql sql, String accountNumber, String accountName, String accountClass, String normalBalanceSide) {
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
        ) values (1, ?, ?, ?, ?, null, true, false, null, current_timestamp, current_timestamp)
    ''', [accountNumber, accountName, accountClass, normalBalanceSide])
  }

  private void switchHome(Path home) {
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, home.toString())
  }

  private static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name)
      return
    }
    System.setProperty(name, value)
  }

  private static final class ExportFixture {

    final int accountCount
    final int openingBalanceCount
    final int voucherCount
    final int lineCount

    private ExportFixture(int accountCount, int openingBalanceCount, int voucherCount, int lineCount) {
      this.accountCount = accountCount
      this.openingBalanceCount = openingBalanceCount
      this.voucherCount = voucherCount
      this.lineCount = lineCount
    }
  }

  private static final class SeededServices {

    final SieImportExportService sieService
    final FiscalYear fiscalYear
    final int accountCount
    final int openingBalanceCount
    final int voucherCount
    final int lineCount

    private SeededServices(
        SieImportExportService sieService,
        FiscalYear fiscalYear,
        int accountCount,
        int openingBalanceCount,
        int voucherCount,
        int lineCount
    ) {
      this.sieService = sieService
      this.fiscalYear = fiscalYear
      this.accountCount = accountCount
      this.openingBalanceCount = openingBalanceCount
      this.voucherCount = voucherCount
      this.lineCount = lineCount
    }
  }
}
