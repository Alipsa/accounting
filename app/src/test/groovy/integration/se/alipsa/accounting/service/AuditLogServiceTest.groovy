package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

import groovy.sql.GroovyRowResult
import groovy.sql.Sql

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.AuditLogEntry
import se.alipsa.accounting.support.AppPaths

import java.nio.file.Path

class AuditLogServiceTest {

  @TempDir
  Path tempDir

  private DatabaseService databaseService
  private AuditLogService auditLogService
  private String previousHome

  @BeforeEach
  void setUp() {
    previousHome = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, tempDir.toString())
    databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    auditLogService = new AuditLogService(databaseService)
  }

  @AfterEach
  void tearDown() {
    restoreProperty(AppPaths.HOME_OVERRIDE_PROPERTY, previousHome)
  }

  @Test
  void standaloneImportExportBackupAndRestoreEventsAreChained() {
    auditLogService.logImport('Importerade SIE', 'jobId=1')
    auditLogService.logExport('Exporterade SIE', 'jobId=2')
    auditLogService.logBackup('Skapade backup', 'archive=backup.zip')
    auditLogService.logRestore('Återställde backup', 'archive=backup.zip')

    List<AuditLogEntry> entries = auditLogService.listEntries(CompanyService.LEGACY_COMPANY_ID)

    assertEquals(4, entries.size())
    assertTrue(entries.any { AuditLogEntry entry -> entry.eventType == AuditLogService.IMPORT })
    assertTrue(entries.any { AuditLogEntry entry -> entry.eventType == AuditLogService.EXPORT })
    assertTrue(entries.any { AuditLogEntry entry -> entry.eventType == AuditLogService.BACKUP })
    assertTrue(entries.any { AuditLogEntry entry -> entry.eventType == AuditLogService.RESTORE })
    assertEquals([], auditLogService.validateIntegrity())
  }

  @Test
  void validateIntegrityDetectsTamperedAuditRow() {
    auditLogService.logImport('Importerade SIE', 'jobId=1')

    databaseService.withTransaction { Sql sql ->
      sql.executeUpdate("update audit_log set summary = 'tampered' where id = 1")
    }

    List<String> problems = auditLogService.validateIntegrity()

    assertFalse(problems.isEmpty())
    assertTrue(problems.any { String problem -> problem.contains('ogiltig hash') })
  }

  @Test
  void rebuildIntegrityChainMakesLiveEntriesSkipArchivedOnesInBetween() {
    long companyId = CompanyService.LEGACY_COMPANY_ID
    auditLogService.logImport('A - kommer förbli levande', 'seed=A')
    auditLogService.logExport('B - arkiveras i efterhand', 'seed=B')
    auditLogService.logBackup('C - kommer förbli levande', 'seed=C')

    databaseService.withTransaction { Sql sql ->
      sql.executeUpdate("update audit_log set archived = true where summary like 'B -%'")
      AuditLogService.rebuildIntegrityChain(sql, companyId)
    }

    databaseService.withTransaction { Sql sql ->
      String hashOfA = firstHashMatching(sql, "summary like 'A -%'", 'entry_hash')
      String previousHashOfC = firstHashMatching(sql, "summary like 'C -%'", 'previous_hash')
      assertEquals(hashOfA, previousHashOfC,
          'C must chain from A (the last live entry), skipping the now-archived B in between')
    }
    assertEquals([], auditLogService.validateIntegrity())
  }

  @Test
  void repairIntegrityForAllCompaniesRestoresRecoverableReferencesAndClosesTheRest() {
    long companyId = CompanyService.LEGACY_COMPANY_ID
    auditLogService.logImport('Kedjeankare', 'seed=anchor')

    databaseService.withTransaction { Sql sql ->
      // Simulates rows corrupted by the pre-V27 archival bug: every reference column was
      // nulled out regardless of event type, without recalculating entry_hash. The actual
      // hash values here don't matter - repairIntegrityForAllCompanies's rebuild step
      // recomputes them from whatever ends up in each row afterwards.
      insertCorruptedArchivedRow(sql, companyId, 'CREATE_VOUCHER',
          'accountingDate=2026-01-05\ndescription=Test\nfiscalYearId=5\nstatus=ACTIVE\nvoucherId=999')
      insertCorruptedArchivedRow(sql, companyId, 'ATTACHMENT_ADDED',
          'attachmentId=42\ncontentType=text/plain\nfileSize=10\nvoucherId=999')
      insertCorruptedArchivedRow(sql, companyId, 'LOCK_PERIOD',
          'accountingPeriodId=7\nfiscalYearId=5\nperiodName=Januari')
      insertCorruptedArchivedRow(sql, companyId, 'VAT_PERIOD_LOCKED',
          'fiscalYearId=5\nperiodName=Q1\nstatus=LOCKED\ntransferVoucherId=888\nvatPeriodId=3')
      // CANCEL_VOUCHER never records fiscalYearId in details (see recordVoucherCancelled) -
      // fiscal_year_id is genuinely unrecoverable here and must be closed out by rebuilding
      // instead, not left permanently invalid.
      insertCorruptedArchivedRow(sql, companyId, 'CANCEL_VOUCHER',
          'accountingDate=2026-01-05\ndescription=Test\nstatus=CANCELLED\nvoucherId=999')

      AuditLogService.repairIntegrityForAllCompanies(sql)
    }

    databaseService.withTransaction { Sql sql ->
      assertReference(sql, 'CREATE_VOUCHER', 'voucher_id', 999L)
      assertReference(sql, 'CREATE_VOUCHER', 'fiscal_year_id', 5L)
      assertReference(sql, 'ATTACHMENT_ADDED', 'attachment_id', 42L)
      assertReference(sql, 'ATTACHMENT_ADDED', 'voucher_id', 999L)
      assertReference(sql, 'LOCK_PERIOD', 'accounting_period_id', 7L)
      assertReference(sql, 'LOCK_PERIOD', 'fiscal_year_id', 5L)
      assertReference(sql, 'VAT_PERIOD_LOCKED', 'vat_period_id', 3L)
      assertReference(sql, 'VAT_PERIOD_LOCKED', 'fiscal_year_id', 5L)
      assertReference(sql, 'VAT_PERIOD_LOCKED', 'voucher_id', 888L,
          'voucher_id must be recovered from transferVoucherId, the key name VAT_PERIOD_LOCKED actually uses')
      assertReference(sql, 'CANCEL_VOUCHER', 'voucher_id', 999L)
      assertReference(sql, 'CANCEL_VOUCHER', 'fiscal_year_id', null,
          'fiscal_year_id was never in this event type\'s details, so it must stay null')
    }

    assertEquals([], auditLogService.validateIntegrity(),
        'Every archived row must validate again, including CANCEL_VOUCHER whose fiscal_year_id could not be recovered')
  }

  private static void insertCorruptedArchivedRow(Sql sql, long companyId, String eventType, String details) {
    sql.executeInsert('''
        insert into audit_log (
            event_type, voucher_id, attachment_id, fiscal_year_id, accounting_period_id, vat_period_id,
            actor, summary, details, previous_hash, entry_hash, created_at, company_id, archived
        ) values (?, null, null, null, null, null, 'desktop-app', ?, ?, 'placeholder', 'placeholder',
                  current_timestamp, ?, true)
    ''', [eventType, "Arkiverad ${eventType}".toString(), details, companyId])
  }

  private static void assertReference(Sql sql, String eventType, String column, Long expected, String message = null) {
    GroovyRowResult row = sql.firstRow(
        "select ${column} as columnValue from audit_log where event_type = ?".toString(), [eventType]
    ) as GroovyRowResult
    Object actual = row.get('columnValue')
    Long actualValue = actual == null ? null : ((Number) actual).longValue()
    if (message != null) {
      assertEquals(expected, actualValue, message)
    } else {
      assertEquals(expected, actualValue)
    }
  }

  private static String firstHashMatching(Sql sql, String whereClause, String column) {
    GroovyRowResult row = sql.firstRow(
        "select ${column} as columnValue from audit_log where ${whereClause}".toString()
    ) as GroovyRowResult
    row.get('columnValue') as String
  }

  private static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name)
      return
    }
    System.setProperty(name, value)
  }
}
