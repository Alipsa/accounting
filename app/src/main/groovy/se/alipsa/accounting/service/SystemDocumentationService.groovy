package se.alipsa.accounting.service

import se.alipsa.accounting.domain.AttachmentMetadata
import se.alipsa.accounting.domain.report.ReportArchive
import se.alipsa.accounting.support.AppPaths

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime

/**
 * Produces in-app system documentation and audit export material.
 */
final class SystemDocumentationService {

  private final MigrationService migrationService
  private final SystemDiagnosticsService diagnosticsService
  private final AttachmentService attachmentService
  private final ReportArchiveService reportArchiveService
  private final AuditLogService auditLogService

  SystemDocumentationService() {
    this(
        new MigrationService(),
        new SystemDiagnosticsService(),
        new AttachmentService(),
        new ReportArchiveService(),
        new AuditLogService()
    )
  }

  SystemDocumentationService(
      MigrationService migrationService,
      SystemDiagnosticsService diagnosticsService,
      AttachmentService attachmentService,
      ReportArchiveService reportArchiveService,
      AuditLogService auditLogService
  ) {
    this.migrationService = migrationService
    this.diagnosticsService = diagnosticsService
    this.attachmentService = attachmentService
    this.reportArchiveService = reportArchiveService
    this.auditLogService = auditLogService
  }

  String renderDocumentation() {
    SystemDiagnosticsSnapshot diagnostics = diagnosticsService.snapshot()
    List<MigrationService.AppliedMigration> migrations = migrationService.listAppliedMigrations()
    List<AttachmentMetadata> attachments = attachmentService.listAllAttachments()
    List<ReportArchive> archives = reportArchiveService.listAllArchives(200)
    List<String> lines = [
        '# Alipsa Accounting systemdokumentation',
        '',
        "Genererad: ${LocalDateTime.now()}".toString(),
        '',
        '## Driftmiljö',
        '',
        "- Applikationskatalog: `${diagnostics.applicationHome}`".toString(),
        "- Databasfil: `${diagnostics.databaseFile}`".toString(),
        "- Schema-version: `${diagnostics.schemaVersion}` av `${diagnostics.expectedSchemaVersion}`".toString(),
        diagnostics.latestBackup == null
            ? '- Senaste backup: ingen registrerad'
            : "- Senaste backup: `${diagnostics.latestBackup.backupPath.fileName}` ${diagnostics.latestBackup.createdAt}".toString(),
        diagnostics.latestSieExportAt == null
            ? '- Senaste SIE-export: ingen registrerad'
            : "- Senaste SIE-export: ${diagnostics.latestSieExportAt} (${diagnostics.latestSieExportSummary})".toString(),
        '',
        '## Integritetskontroller',
        '',
        diagnostics.verificationReport.ok ? '- Status: OK' : '- Status: FEL',
        diagnostics.verificationReport.errors.isEmpty()
            ? '- Kritiska fel: inga'
            : "- Kritiska fel: ${diagnostics.verificationReport.errors.size()}".toString(),
        diagnostics.verificationReport.warnings.isEmpty()
            ? '- Varningar: inga'
            : "- Varningar: ${diagnostics.verificationReport.warnings.size()}".toString(),
        '',
        '## Schema och migrationer',
        ''
    ]
    migrations.each { MigrationService.AppliedMigration migration ->
      lines << "- V${migration.version}: `${migration.scriptName}` installerad ${migration.installedAt}".toString()
    }
    lines.addAll([
        '',
        '## Bokföringsflöden',
        '',
        '- Verifikationer bokförs med nummerserier per räkenskapsår och serie.',
        '- Bokförda verifikationer är append-only och korrigeras genom korrigeringsverifikation.',
        '- Momsperioder kan rapporteras och låsas för att blockera otillåtna ändringar.',
        '- Årsbokslut stänger resultatkonton mot konto 2099 och skapar nästa års ingående balanser.',
        '',
        '## Import och export',
        '',
        '- SIE4 import/export stöds med importlogg och filhash för dubblettskydd.',
        '- Rapportarkiv sparar PDF/CSV med checksumma och parametrar.',
        "- Bilagearkiv: ${attachments.size()} bilagor registrerade.".toString(),
        "- Rapportarkiv: ${archives.size()} arkivposter i de senaste 200 raderna.".toString(),
        '',
        '## Backup och restore',
        '',
        '- Backupformat: ZIP med H2 SCRIPT-dump, manifest och checksummor för bilage- och rapportfiler.',
        '- Restore verifierar manifest och checksummor innan databasen och filarkiven återskapas.',
        '',
        '## Bevarandespärrar',
        '',
        '- Bokföringsdata, bilagor och rapportarkiv får inte rensas före sju års bevarandetid.',
        '',
        '## Audit-logg',
        ''
    ])
    auditLogService.listAllEntries(20).each { entry ->
      lines << "- ${entry.createdAt}: `${entry.eventType}` ${entry.summary}".toString()
    }
    lines.join('\n')
  }

  Path exportDocumentation(Path targetPath = null) {
    AppPaths.ensureDirectoryStructure()
    Path safeTarget = targetPath == null
        ? AppPaths.docsDirectory().resolve('system-documentation.md')
        : targetPath.toAbsolutePath().normalize()
    Files.createDirectories(safeTarget.parent)
    Files.writeString(safeTarget, renderDocumentation(), StandardCharsets.UTF_8)
    safeTarget
  }
}
