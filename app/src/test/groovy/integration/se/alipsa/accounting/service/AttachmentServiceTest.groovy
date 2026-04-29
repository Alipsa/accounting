package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertArrayEquals
import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

import groovy.sql.GroovyRowResult
import groovy.sql.Sql

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.AttachmentMetadata
import se.alipsa.accounting.domain.AuditLogEntry
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.Voucher
import se.alipsa.accounting.domain.VoucherLine
import se.alipsa.accounting.support.AppPaths

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

class AttachmentServiceTest {

  @TempDir
  Path tempDir

  private DatabaseService databaseService
  private AuditLogService auditLogService
  private AttachmentService attachmentService
  private RetentionPolicyService retentionPolicyService
  private FiscalYearService fiscalYearService
  private AccountingPeriodService accountingPeriodService
  private VoucherService voucherService
  private FiscalYear fiscalYear
  private String previousHome

  @BeforeEach
  void setUp() {
    previousHome = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, tempDir.toString())
    databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    auditLogService = new AuditLogService(databaseService)
    retentionPolicyService = new RetentionPolicyService(
        Clock.fixed(java.time.Instant.parse('2033-12-31T23:59:59Z'), ZoneId.systemDefault())
    )
    attachmentService = new AttachmentService(databaseService, auditLogService, retentionPolicyService)
    accountingPeriodService = new AccountingPeriodService(databaseService, auditLogService)
    fiscalYearService = new FiscalYearService(databaseService, accountingPeriodService, auditLogService)
    voucherService = new VoucherService(databaseService, auditLogService)
    fiscalYear = fiscalYearService.createFiscalYear(
        CompanyService.LEGACY_COMPANY_ID,
        '2026',
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 12, 31)
    )
    insertTestAccounts()
  }

  @AfterEach
  void tearDown() {
    restoreProperty(AppPaths.HOME_OVERRIDE_PROPERTY, previousHome)
  }

  @Test
  void attachmentIsStoredWithChecksumAndVoucherAuditEntry() {
    Voucher voucher = voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 6, 1),
        'Bilagor',
        balancedLines(125.00G)
    )
    Path source = tempDir.resolve('receipt.txt')
    Files.writeString(source, 'kvitto 2026-06-01', StandardCharsets.UTF_8)

    AttachmentMetadata attachment = attachmentService.addAttachment(voucher.id, source)
    List<AttachmentMetadata> attachments = attachmentService.listAttachments(voucher.id)
    List<AuditLogEntry> auditEntries = auditLogService.listEntriesForVoucher(voucher.id)

    assertEquals(1, attachments.size())
    assertEquals(attachment.id, attachments.first().id)
    assertEquals('ACTIVE', attachment.status)
    assertTrue(Files.exists(attachmentService.resolveStoredPath(attachment)))
    assertTrue(attachmentService.verifyAttachment(attachment.id))
    assertEquals([], attachmentService.findIntegrityFailures(CompanyService.LEGACY_COMPANY_ID))
    assertArrayEquals(Files.readAllBytes(source), attachmentService.readAttachment(attachment.id))
    assertTrue(auditEntries.any { AuditLogEntry entry -> entry.eventType == AuditLogService.ATTACHMENT_ADDED })
  }

  @Test
  void checksumValidationDetectsTamperedAttachment() {
    Voucher voucher = voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 6, 2),
        'Tamper test',
        balancedLines(80.00G)
    )
    Path source = tempDir.resolve('invoice.txt')
    Files.writeString(source, 'original', StandardCharsets.UTF_8)

    AttachmentMetadata attachment = attachmentService.addAttachment(voucher.id, source)
    Files.writeString(attachmentService.resolveStoredPath(attachment), 'modified', StandardCharsets.UTF_8)

    assertFalse(attachmentService.verifyAttachment(attachment.id))
    assertEquals([attachment.id], attachmentService.findIntegrityFailures(CompanyService.LEGACY_COMPANY_ID)*.id)
  }

  @Test
  void pathTraversalAttemptInStoredMetadataIsRejected() {
    Voucher voucher = voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 6, 3),
        'Traversal test',
        balancedLines(40.00G)
    )
    Path source = tempDir.resolve('note.txt')
    Files.writeString(source, 'note', StandardCharsets.UTF_8)

    AttachmentMetadata attachment = attachmentService.addAttachment(voucher.id, source)
    databaseService.withTransaction { Sql sql ->
      sql.executeUpdate("update attachment set storage_path = '../../../etc/passwd' where id = ?", [attachment.id])
    }

    SecurityException exception = assertThrows(SecurityException) {
      attachmentService.readAttachment(attachment.id)
    }

    assertTrue(exception.message.contains('utanför bilagearkivet'))
    assertEquals([attachment.id], attachmentService.findIntegrityFailures(CompanyService.LEGACY_COMPANY_ID)*.id)
  }

  @Test
  void addAttachmentCreatesPendingThenActivates() {
    Voucher voucher = voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 6, 4),
        'Pending test',
        balancedLines(10.00G)
    )
    Path source = tempDir.resolve('pending.txt')
    Files.writeString(source, 'pending content', StandardCharsets.UTF_8)

    AttachmentMetadata attachment = attachmentService.addAttachment(voucher.id, source)

    assertEquals('ACTIVE', attachment.status)
    assertTrue(attachmentService.verifyAttachment(attachment.id))
  }

  @Test
  void copyFailureMarksAttachmentFailedAndPropagatesException() {
    Voucher voucher = voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 6, 5),
        'Copy failure test',
        balancedLines(10.00G)
    )
    Path source = tempDir.resolve('copy_failure.txt')
    Files.writeString(source, 'copy failure content', StandardCharsets.UTF_8)
    AttachmentService failingAttachmentService = new AttachmentService(
        databaseService,
        auditLogService,
        retentionPolicyService,
        new FailingCopyFileOperations()
    )

    IOException exception = assertThrows(IOException) {
      failingAttachmentService.addAttachment(voucher.id, source)
    }

    assertEquals('simulated copy failure', exception.message)
    GroovyRowResult failed = databaseService.withSql { Sql sql ->
      sql.firstRow('''
          select id,
                 status
            from attachment
           where voucher_id = ?
      ''', [voucher.id]) as GroovyRowResult
    }
    assertEquals('FAILED', failed.get('status'))
    long failedId = ((Number) failed.get('id')).longValue()
    assertEquals([failedId], attachmentService.findIntegrityFailures(CompanyService.LEGACY_COMPANY_ID)*.id)
  }

  @Test
  void recoverPendingWithOkFile() {
    Voucher voucher = voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 6, 6),
        'Recover ok test',
        balancedLines(10.00G)
    )
    Path source = tempDir.resolve('recover_ok.txt')
    Files.writeString(source, 'recover me', StandardCharsets.UTF_8)

    AttachmentMetadata attachment = attachmentService.addAttachment(voucher.id, source)
    // Simulate crash: set back to PENDING but leave file intact
    databaseService.withTransaction { Sql sql ->
      sql.executeUpdate("update attachment set status = 'PENDING' where id = ?", [attachment.id])
    }

    AttachmentRecoveryReport report = attachmentService.recoverOnStartup()

    assertEquals(1, report.activated)
    assertEquals(0, report.failed)
    assertEquals(0, report.deletionsDone)
    assertTrue(report.orphanFiles.isEmpty())

    AttachmentMetadata recovered = attachmentService.findAttachment(attachment.id)
    assertEquals('ACTIVE', recovered.status)
    List<AuditLogEntry> entries = auditLogService.listEntriesForVoucher(voucher.id)
    assertTrue(entries.any { it.eventType == AuditLogService.ATTACHMENT_RECOVERED })
  }

  @Test
  void recoverPendingWithMissingFile() {
    Voucher voucher = voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 6, 7),
        'Recover missing test',
        balancedLines(10.00G)
    )
    Path source = tempDir.resolve('recover_missing.txt')
    Files.writeString(source, 'gone', StandardCharsets.UTF_8)

    AttachmentMetadata attachment = attachmentService.addAttachment(voucher.id, source)
    databaseService.withTransaction { Sql sql ->
      sql.executeUpdate("update attachment set status = 'PENDING' where id = ?", [attachment.id])
    }
    Files.deleteIfExists(attachmentService.resolveStoredPath(attachment))

    AttachmentRecoveryReport report = attachmentService.recoverOnStartup()

    assertEquals(0, report.activated)
    assertEquals(1, report.failed)
    assertEquals(0, report.deletionsDone)

    AttachmentMetadata recovered = attachmentService.findAttachment(attachment.id)
    assertEquals('FAILED', recovered.status)
    assertEquals([attachment.id], attachmentService.findIntegrityFailures(CompanyService.LEGACY_COMPANY_ID)*.id)
  }

  @Test
  void recoverPendingWithBadChecksum() {
    Voucher voucher = voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 6, 8),
        'Recover checksum test',
        balancedLines(10.00G)
    )
    Path source = tempDir.resolve('recover_checksum.txt')
    Files.writeString(source, 'original', StandardCharsets.UTF_8)

    AttachmentMetadata attachment = attachmentService.addAttachment(voucher.id, source)
    databaseService.withTransaction { Sql sql ->
      sql.executeUpdate("update attachment set status = 'PENDING' where id = ?", [attachment.id])
    }
    Files.writeString(attachmentService.resolveStoredPath(attachment), 'tampered', StandardCharsets.UTF_8)

    AttachmentRecoveryReport report = attachmentService.recoverOnStartup()

    assertEquals(0, report.activated)
    assertEquals(1, report.failed)
    assertEquals(0, report.deletionsDone)

    AttachmentMetadata recovered = attachmentService.findAttachment(attachment.id)
    assertEquals('FAILED', recovered.status)
    assertEquals([attachment.id], attachmentService.findIntegrityFailures(CompanyService.LEGACY_COMPANY_ID)*.id)
  }

  @Test
  void deleteAttachmentSetsDeleted() {
    Voucher voucher = voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 6, 9),
        'Delete test',
        balancedLines(10.00G)
    )
    Path source = tempDir.resolve('delete_me.txt')
    Files.writeString(source, 'delete me', StandardCharsets.UTF_8)

    AttachmentMetadata attachment = attachmentService.addAttachment(voucher.id, source)
    attachmentService.deleteAttachment(attachment.id)

    assertEquals([], attachmentService.listAttachments(voucher.id))
    AttachmentMetadata deleted = attachmentService.findAttachment(attachment.id)
    assertEquals('DELETED', deleted.status)
    assertFalse(Files.exists(attachmentService.resolveStoredPath(attachment)))
  }

  @Test
  void recoverPendingDeleteWithFile() {
    Voucher voucher = voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 6, 10),
        'Recover delete test',
        balancedLines(10.00G)
    )
    Path source = tempDir.resolve('pending_delete.txt')
    Files.writeString(source, 'pending delete', StandardCharsets.UTF_8)

    AttachmentMetadata attachment = attachmentService.addAttachment(voucher.id, source)
    databaseService.withTransaction { Sql sql ->
      sql.executeUpdate("update attachment set status = 'PENDING_DELETE' where id = ?", [attachment.id])
    }
    // file still exists

    AttachmentRecoveryReport report = attachmentService.recoverOnStartup()

    assertEquals(0, report.activated)
    assertEquals(0, report.failed)
    assertEquals(1, report.deletionsDone)
    assertFalse(Files.exists(attachmentService.resolveStoredPath(attachment)))

    AttachmentMetadata deleted = attachmentService.findAttachment(attachment.id)
    assertEquals('DELETED', deleted.status)
  }

  @Test
  void recoverPendingDeleteMissingFile() {
    Voucher voucher = voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 6, 11),
        'Recover delete missing test',
        balancedLines(10.00G)
    )
    Path source = tempDir.resolve('pending_delete_missing.txt')
    Files.writeString(source, 'already gone', StandardCharsets.UTF_8)

    AttachmentMetadata attachment = attachmentService.addAttachment(voucher.id, source)
    databaseService.withTransaction { Sql sql ->
      sql.executeUpdate("update attachment set status = 'PENDING_DELETE' where id = ?", [attachment.id])
    }
    Files.deleteIfExists(attachmentService.resolveStoredPath(attachment))

    AttachmentRecoveryReport report = attachmentService.recoverOnStartup()

    assertEquals(0, report.activated)
    assertEquals(0, report.failed)
    assertEquals(1, report.deletionsDone)

    AttachmentMetadata deleted = attachmentService.findAttachment(attachment.id)
    assertEquals('DELETED', deleted.status)
  }

  @Test
  void recoverPendingDeleteKeepsStatusAndReportsWarningWhenFileCannotBeDeleted() {
    Voucher voucher = voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 6, 12),
        'Recover delete failure test',
        balancedLines(10.00G)
    )
    Path source = tempDir.resolve('pending_delete_failure.txt')
    Files.writeString(source, 'delete failure', StandardCharsets.UTF_8)

    AttachmentMetadata attachment = attachmentService.addAttachment(voucher.id, source)
    databaseService.withTransaction { Sql sql ->
      sql.executeUpdate("update attachment set status = 'PENDING_DELETE' where id = ?", [attachment.id])
    }
    AttachmentService failingDeleteService = new AttachmentService(
        databaseService,
        auditLogService,
        retentionPolicyService,
        new FailingDeleteFileOperations()
    )

    AttachmentRecoveryReport report = failingDeleteService.recoverOnStartup()

    assertEquals(0, report.deletionsDone)
    assertEquals('PENDING_DELETE', attachmentService.findAttachment(attachment.id).status)
    assertTrue(Files.exists(attachmentService.resolveStoredPath(attachment)))
    assertTrue(report.warnings.any { String warning -> warning.contains('leaving PENDING_DELETE') })
  }

  @Test
  void orphanFileWithoutDbRow() {
    // Create a file directly in the attachments directory without a DB row
    Path attachmentsDir = AppPaths.attachmentsDirectory()
    Path orphanDir = attachmentsDir.resolve('voucher-99999')
    Files.createDirectories(orphanDir)
    Path orphanFile = orphanDir.resolve('orphan.txt')
    Files.writeString(orphanFile, 'orphan', StandardCharsets.UTF_8)

    AttachmentRecoveryReport report = attachmentService.recoverOnStartup()

    assertEquals(1, report.orphanFiles.size())
  }

  @Test
  void orphanScanNormalizesWindowsSeparatorsFromDatabasePaths() {
    Voucher voucher = voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 6, 12),
        'Windows path test',
        balancedLines(10.00G)
    )
    Path storedFile = AppPaths.attachmentsDirectory().resolve('voucher-windows').resolve('known.txt')
    Files.createDirectories(storedFile.parent)
    Files.writeString(storedFile, 'known', StandardCharsets.UTF_8)

    databaseService.withTransaction { Sql sql ->
      sql.executeInsert('''
          insert into attachment (
              voucher_id,
              original_file_name,
              content_type,
              storage_path,
              checksum_sha256,
              file_size,
              created_at,
              status
          ) values (?, ?, ?, ?, ?, ?, current_timestamp, ?)
      ''', [
          voucher.id,
          'known.txt',
          'text/plain',
          'voucher-windows\\known.txt',
          sha256(storedFile),
          Files.size(storedFile),
          'ACTIVE'
      ])
    }

    AttachmentRecoveryReport report = attachmentService.recoverOnStartup()

    assertFalse(report.orphanFiles.contains(storedFile))
  }

  @Test
  void readDeletedAttachmentThrows() {
    Voucher voucher = voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 6, 13),
        'Read deleted test',
        balancedLines(10.00G)
    )
    Path source = tempDir.resolve('read_deleted.txt')
    Files.writeString(source, 'deleted', StandardCharsets.UTF_8)

    AttachmentMetadata attachment = attachmentService.addAttachment(voucher.id, source)
    attachmentService.deleteAttachment(attachment.id)

    IllegalArgumentException exception = assertThrows(IllegalArgumentException) {
      attachmentService.readAttachment(attachment.id)
    }
    assertTrue(exception.message.contains('not active'))
  }

  @Test
  void deleteDeletedAttachmentThrows() {
    Voucher voucher = voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 6, 14),
        'Delete deleted test',
        balancedLines(10.00G)
    )
    Path source = tempDir.resolve('delete_deleted.txt')
    Files.writeString(source, 'deleted', StandardCharsets.UTF_8)

    AttachmentMetadata attachment = attachmentService.addAttachment(voucher.id, source)
    attachmentService.deleteAttachment(attachment.id)

    IllegalArgumentException exception = assertThrows(IllegalArgumentException) {
      attachmentService.deleteAttachment(attachment.id)
    }
    assertTrue(exception.message.contains('not active'))
  }

  private void insertTestAccounts() {
    databaseService.withTransaction { Sql sql ->
      insertAccount(sql, '1510', 'Kundfordringar', 'ASSET', 'DEBIT')
      insertAccount(sql, '3010', 'Försäljning', 'INCOME', 'CREDIT')
    }
  }

  private static void insertAccount(
      Sql sql,
      String accountNumber,
      String accountName,
      String accountClass,
      String normalBalanceSide
  ) {
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

  private static List<VoucherLine> balancedLines(BigDecimal amount) {
    [
        new VoucherLine(null, null, 0, null, '1510', null, 'Kundfordran', amount, 0.00G),
        new VoucherLine(null, null, 0, null, '3010', null, 'Försäljning', 0.00G, amount)
    ]
  }

  private static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name)
      return
    }
    System.setProperty(name, value)
  }

  private static String sha256(Path file) {
    java.security.MessageDigest digest = java.security.MessageDigest.getInstance('SHA-256')
    Files.newInputStream(file).withCloseable { InputStream input ->
      byte[] buffer = new byte[8192]
      int bytesRead = 0
      while ((bytesRead = input.read(buffer)) >= 0) {
        if (bytesRead > 0) {
          digest.update(buffer, 0, bytesRead)
        }
      }
    }
    HexFormat.of().formatHex(digest.digest())
  }

  private static final class FailingCopyFileOperations implements AttachmentFileOperations {

    @Override
    void copy(Path source, Path target) throws IOException {
      throw new IOException('simulated copy failure')
    }

    @Override
    boolean deleteIfExists(Path path) throws IOException {
      Files.deleteIfExists(path)
    }
  }

  private static final class FailingDeleteFileOperations implements AttachmentFileOperations {

    @Override
    void copy(Path source, Path target) throws IOException {
      Files.copy(source, target)
    }

    @Override
    boolean deleteIfExists(Path path) throws IOException {
      throw new IOException('simulated delete failure')
    }
  }
}
