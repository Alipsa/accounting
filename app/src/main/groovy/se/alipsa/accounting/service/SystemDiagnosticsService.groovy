package se.alipsa.accounting.service

import se.alipsa.accounting.domain.AuditLogEntry
import se.alipsa.accounting.support.AppPaths

import java.nio.file.Path

/**
 * Aggregates current system diagnostics for the in-app diagnostics panel.
 */
final class SystemDiagnosticsService {

  private final MigrationService migrationService
  private final StartupVerificationService startupVerificationService
  private final BackupService backupService
  private final AuditLogService auditLogService

  SystemDiagnosticsService() {
    this(new MigrationService(), new StartupVerificationService(), new BackupService(), new AuditLogService())
  }

  SystemDiagnosticsService(
      MigrationService migrationService,
      StartupVerificationService startupVerificationService,
      BackupService backupService,
      AuditLogService auditLogService
  ) {
    this.migrationService = migrationService
    this.startupVerificationService = startupVerificationService
    this.backupService = backupService
    this.auditLogService = auditLogService
  }

  SystemDiagnosticsSnapshot snapshot() {
    StartupVerificationReport verificationReport = startupVerificationService.verify()
    BackupSummary latestBackup = backupService.listBackups(1).find()
    AuditLogEntry latestSieExport = auditLogService.listAllEntries(200).find { AuditLogEntry entry ->
      entry.eventType == AuditLogService.EXPORT && entry.summary?.startsWith('Exporterade SIE')
    }
    Path databaseFile = AppPaths.databaseBasePath().resolveSibling('accounting.mv.db')
    new SystemDiagnosticsSnapshot(
        AppPaths.applicationHome(),
        databaseFile,
        migrationService.currentSchemaVersion(),
        migrationService.expectedSchemaVersion(),
        verificationReport,
        latestBackup,
        latestSieExport?.createdAt,
        latestSieExport?.summary
    )
  }
}
