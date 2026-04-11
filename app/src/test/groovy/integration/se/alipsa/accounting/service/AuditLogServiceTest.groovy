package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

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

    List<AuditLogEntry> entries = auditLogService.listEntries()

    assertEquals(4, entries.size())
    assertTrue(entries.any { AuditLogEntry entry -> entry.eventType == AuditLogService.IMPORT })
    assertTrue(entries.any { AuditLogEntry entry -> entry.eventType == AuditLogService.EXPORT })
    assertTrue(entries.any { AuditLogEntry entry -> entry.eventType == AuditLogService.BACKUP })
    assertTrue(entries.any { AuditLogEntry entry -> entry.eventType == AuditLogService.RESTORE })
    assertEquals([], auditLogService.validateIntegrity())
  }

  private static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name)
      return
    }
    System.setProperty(name, value)
  }
}
