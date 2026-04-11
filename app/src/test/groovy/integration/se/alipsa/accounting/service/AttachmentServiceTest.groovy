package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertArrayEquals
import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

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
import java.time.LocalDate

class AttachmentServiceTest {

  @TempDir
  Path tempDir

  private DatabaseService databaseService
  private AuditLogService auditLogService
  private AttachmentService attachmentService
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
    attachmentService = new AttachmentService(databaseService, auditLogService)
    accountingPeriodService = new AccountingPeriodService(databaseService, auditLogService)
    fiscalYearService = new FiscalYearService(databaseService, accountingPeriodService, auditLogService)
    voucherService = new VoucherService(databaseService, auditLogService)
    fiscalYear = fiscalYearService.createFiscalYear(
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
    Voucher voucher = voucherService.createDraft(
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
    assertTrue(Files.exists(attachmentService.resolveStoredPath(attachment)))
    assertTrue(attachmentService.verifyAttachment(attachment.id))
    assertEquals([], attachmentService.findIntegrityFailures())
    assertArrayEquals(Files.readAllBytes(source), attachmentService.readAttachment(attachment.id))
    assertTrue(auditEntries.any { AuditLogEntry entry -> entry.eventType == AuditLogService.ATTACHMENT_ADDED })
  }

  @Test
  void checksumValidationDetectsTamperedAttachment() {
    Voucher voucher = voucherService.createDraft(
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
    assertEquals([attachment.id], attachmentService.findIntegrityFailures()*.id)
  }

  private void insertTestAccounts() {
    databaseService.withTransaction { groovy.sql.Sql sql ->
      insertAccount(sql, '1510', 'Kundfordringar', 'ASSET', 'DEBIT')
      insertAccount(sql, '3010', 'Försäljning', 'INCOME', 'CREDIT')
    }
  }

  private static void insertAccount(
      groovy.sql.Sql sql,
      String accountNumber,
      String accountName,
      String accountClass,
      String normalBalanceSide
  ) {
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

  private static List<VoucherLine> balancedLines(BigDecimal amount) {
    [
        new VoucherLine(null, null, 0, '1510', null, 'Kundfordran', amount, 0.00G),
        new VoucherLine(null, null, 0, '3010', null, 'Försäljning', 0.00G, amount)
    ]
  }

  private static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name)
      return
    }
    System.setProperty(name, value)
  }
}
