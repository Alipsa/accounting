package se.alipsa.accounting.service

import se.alipsa.accounting.domain.report.ReportArchive
import se.alipsa.accounting.support.AppPaths

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime

/**
 * Verifies integrity and runtime configuration during startup and diagnostics refresh.
 */
final class StartupVerificationService {

  private final DatabaseService databaseService
  private final ReportIntegrityService reportIntegrityService
  private final ReportArchiveService reportArchiveService
  private final AttachmentService attachmentService

  StartupVerificationService() {
    this(DatabaseService.instance, new ReportIntegrityService(), new ReportArchiveService(), new AttachmentService())
  }

  StartupVerificationService(
      DatabaseService databaseService,
      ReportIntegrityService reportIntegrityService,
      ReportArchiveService reportArchiveService,
      AttachmentService attachmentService
  ) {
    this.databaseService = databaseService
    this.reportIntegrityService = reportIntegrityService
    this.reportArchiveService = reportArchiveService
    this.attachmentService = attachmentService
  }

  StartupVerificationReport verify() {
    List<String> errors = []
    List<String> warnings = []

    try {
      databaseService.databaseUrl()
    } catch (Exception exception) {
      errors << (exception.message ?: 'Databaskonfigurationen är ogiltig.')
    }

    if (!Files.exists(AppPaths.databaseBasePath().resolveSibling('accounting.mv.db'))) {
      warnings << 'Databasfilen finns inte ännu eller har inte skapats.'
    }

    AttachmentRecoveryReport recoveryReport = attachmentService.recoverOnStartup()
    if (recoveryReport.activated > 0 || recoveryReport.failed > 0 || recoveryReport.deletionsDone > 0) {
      warnings << ("Bilageåterställning: ${recoveryReport.activated} aktiverade, ${recoveryReport.failed} misslyckade, ${recoveryReport.deletionsDone} borttagningar slutförda." as String)
    }
    recoveryReport.orphanFiles.each { Path orphan ->
      warnings << ("Orphan-bilaga på disk utan DB-rad: ${orphan}" as String)
    }
    warnings.addAll(recoveryReport.warnings)

    errors.addAll(reportIntegrityService.listCriticalProblems())
    reportArchiveService.findAllIntegrityFailures().each { ReportArchive archive ->
      errors << ("Rapportarkiv ${archive.id} har avvikande checksumma eller saknas på disk." as String)
    }

    new StartupVerificationReport(LocalDateTime.now(), errors.isEmpty(), errors, warnings)
  }
}
