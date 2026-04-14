package se.alipsa.accounting.service

import se.alipsa.accounting.domain.report.ReportArchive
import se.alipsa.accounting.support.AppPaths

import java.nio.file.Files
import java.time.LocalDateTime

/**
 * Verifies integrity and runtime configuration during startup and diagnostics refresh.
 */
final class StartupVerificationService {

  private final DatabaseService databaseService
  private final ReportIntegrityService reportIntegrityService
  private final ReportArchiveService reportArchiveService

  StartupVerificationService() {
    this(DatabaseService.instance, new ReportIntegrityService(), new ReportArchiveService())
  }

  StartupVerificationService(
      DatabaseService databaseService,
      ReportIntegrityService reportIntegrityService,
      ReportArchiveService reportArchiveService
  ) {
    this.databaseService = databaseService
    this.reportIntegrityService = reportIntegrityService
    this.reportArchiveService = reportArchiveService
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

    errors.addAll(reportIntegrityService.listCriticalProblems())
    reportArchiveService.findAllIntegrityFailures().each { ReportArchive archive ->
      errors << ("Rapportarkiv ${archive.id} har avvikande checksumma eller saknas på disk." as String)
    }

    new StartupVerificationReport(LocalDateTime.now(), errors.isEmpty(), errors, warnings)
  }
}
