package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

import groovy.sql.GroovyRowResult
import groovy.sql.Sql

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.CompanySettings
import se.alipsa.accounting.domain.VatPeriodicity
import se.alipsa.accounting.domain.VoucherLine
import se.alipsa.accounting.domain.report.ReportSelection
import se.alipsa.accounting.domain.report.ReportType
import se.alipsa.accounting.support.AppPaths

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupServiceTest {

  @TempDir
  Path tempDir

  private String previousHome

  @BeforeEach
  void setUp() {
    previousHome = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
  }

  @AfterEach
  void tearDown() {
    restoreProperty(AppPaths.HOME_OVERRIDE_PROPERTY, previousHome)
  }

  @Test
  void backupCanBeCreatedVerifiedAndRestored() {
    Path sourceHome = tempDir.resolve('source-home')
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, sourceHome.toString())
    DatabaseService databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    seedEnvironment(databaseService)

    BackupService backupService = createBackupService(databaseService)
    Path backupPath = AppPaths.backupsDirectory().resolve('accounting-backup.zip')

    BackupResult backupResult = backupService.createBackup(backupPath)

    assertTrue(Files.isRegularFile(backupPath))
    assertEquals(1, backupService.listBackups(10).size())
    assertEquals(databaseService.currentSchemaVersion(), backupResult.summary.schemaVersion)

    Path restoredHome = tempDir.resolve('restored-home')
    RestoreResult restoreResult = backupService.restoreBackup(backupPath, restoredHome)

    assertEquals(1, restoreResult.restoredAttachmentCount)
    assertEquals(1, restoreResult.restoredReportCount)

    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, restoredHome.toString())
    DatabaseService restoredDatabase = DatabaseService.newForTesting()
    restoredDatabase.initialize()
    restoredDatabase.withSql { Sql sql ->
      assertEquals(1, count(sql, 'company'))
      assertEquals(1, count(sql, 'company_settings'))
      assertEquals(1, count(sql, 'voucher'))
      assertEquals(1, count(sql, 'attachment'))
      assertEquals(1, count(sql, 'report_archive'))
      assertEquals(14, countSchemaVersion(sql))
    }
  }

  @Test
  void restoreRejectsBackupWithPathTraversalInManifest() {
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, tempDir.resolve('source-home').toString())
    DatabaseService databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    BackupService backupService = createBackupService(databaseService)
    Path backupPath = tempDir.resolve('malicious-backup.zip')
    createMaliciousBackup(backupPath)

    IllegalArgumentException exception = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException) {
      backupService.restoreBackup(backupPath, tempDir.resolve('restored-home'))
    }

    assertTrue(exception.message.contains('otillåten sökväg'))
    assertTrue(!Files.exists(tempDir.resolve('evil.txt')))
  }

  private void seedEnvironment(DatabaseService databaseService) {
    AuditLogService auditLogService = new AuditLogService(databaseService)
    AccountingPeriodService accountingPeriodService = new AccountingPeriodService(databaseService, auditLogService)
    FiscalYearService fiscalYearService = new FiscalYearService(databaseService, accountingPeriodService, auditLogService)
    CompanySettingsService companySettingsService = new CompanySettingsService(databaseService)
    VoucherService voucherService = new VoucherService(databaseService, auditLogService)
    AttachmentService attachmentService = new AttachmentService(databaseService, auditLogService)
    ReportArchiveService reportArchiveService = new ReportArchiveService(databaseService)

    companySettingsService.save(new CompanySettings(1L, 'Testbolaget AB', '556677-8899', 'SEK', 'sv-SE', VatPeriodicity.MONTHLY))
    def fiscalYear = fiscalYearService.createFiscalYear('2026', LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
    databaseService.withTransaction { Sql sql ->
      insertAccount(sql, '1510', 'Kundfordringar', 'ASSET', 'DEBIT')
      insertAccount(sql, '1930', 'Bankkonto', 'ASSET', 'DEBIT')
      insertAccount(sql, '2099', 'Årets resultat', 'EQUITY', 'CREDIT')
      insertAccount(sql, '3010', 'Försäljning', 'INCOME', 'CREDIT')
    }
    def voucher = voucherService.createAndBook(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 1, 10),
        'Försäljning',
        [
            new VoucherLine(null, null, 0, '1510', null, 'Kundfordran', 500.00G, 0.00G),
            new VoucherLine(null, null, 0, '3010', null, 'Försäljning', 0.00G, 500.00G)
        ]
    )
    Path attachmentFile = tempDir.resolve('receipt.txt')
    Files.writeString(attachmentFile, 'verifikation')
    attachmentService.addAttachment(voucher.id, attachmentFile)
    reportArchiveService.archiveReport(
        new ReportSelection(ReportType.VOUCHER_LIST, fiscalYear.id, null, fiscalYear.startDate, fiscalYear.endDate),
        'PDF',
        'fake report'.bytes
    )
  }

  private BackupService createBackupService(DatabaseService databaseService) {
    AuditLogService auditLogService = new AuditLogService(databaseService)
    AttachmentService attachmentService = new AttachmentService(databaseService, auditLogService)
    ReportArchiveService reportArchiveService = new ReportArchiveService(databaseService)
    VoucherService voucherService = new VoucherService(databaseService, auditLogService)
    new BackupService(
        databaseService,
        attachmentService,
        reportArchiveService,
        auditLogService,
        new MigrationService(databaseService),
        new ReportIntegrityService(voucherService, attachmentService, auditLogService)
    )
  }

  private static void insertAccount(Sql sql, String accountNumber, String accountName, String accountClass, String normalBalanceSide) {
    sql.executeInsert('''
        insert into account (
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
        ) values (?, ?, ?, ?, null, true, false, null, current_timestamp, current_timestamp)
    ''', [accountNumber, accountName, accountClass, normalBalanceSide])
  }

  private static int count(Sql sql, String tableName) {
    String query = 'select count(*) as total from ' + tableName
    GroovyRowResult row = sql.firstRow(query) as GroovyRowResult
    ((Number) row.get('total')).intValue()
  }

  private static int countSchemaVersion(Sql sql) {
    GroovyRowResult row = sql.firstRow('select coalesce(max(version), 0) as version from schema_version') as GroovyRowResult
    ((Number) row.get('version')).intValue()
  }

  private static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name)
      return
    }
    System.setProperty(name, value)
  }

  private static void createMaliciousBackup(Path backupPath) {
    byte[] scriptBytes = 'create table demo(id int);'.getBytes(StandardCharsets.UTF_8)
    byte[] payloadBytes = 'owned'.getBytes(StandardCharsets.UTF_8)
    String manifest = [
        'formatVersion=1',
        "createdAt=${LocalDateTime.of(2026, 1, 1, 0, 0)}",
        'schemaVersion=14',
        'databasePath=database/script.sql',
        "databaseChecksumSha256=${sha256(scriptBytes)}",
        "databaseSizeBytes=${scriptBytes.length}",
        "FILE\tATTACHMENT\t../../evil.txt\t${sha256(payloadBytes)}\t${payloadBytes.length}"
    ].join('\n')
    Files.createDirectories(backupPath.parent)
    ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(backupPath), StandardCharsets.UTF_8)
    zip.withCloseable {
      writeZipEntry(zip, 'database/script.sql', scriptBytes)
      writeZipEntry(zip, 'manifest.txt', manifest.getBytes(StandardCharsets.UTF_8))
      writeZipEntry(zip, '../../evil.txt', payloadBytes)
    }
  }

  private static void writeZipEntry(ZipOutputStream zip, String name, byte[] content) {
    ZipEntry entry = new ZipEntry(name)
    zip.putNextEntry(entry)
    zip.write(content)
    zip.closeEntry()
  }

  private static String sha256(byte[] content) {
    MessageDigest digest = MessageDigest.getInstance('SHA-256')
    HexFormat.of().formatHex(digest.digest(content))
  }
}
