package se.alipsa.accounting.service

import groovy.sql.Sql
import groovy.transform.CompileStatic

import org.h2.tools.RunScript
import org.h2.tools.Script

import se.alipsa.accounting.domain.AttachmentMetadata
import se.alipsa.accounting.domain.report.ReportArchive
import se.alipsa.accounting.support.AppPaths

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.sql.Connection
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Creates and restores verified backups containing an H2 script dump and archived files.
 */
@CompileStatic
final class BackupService {

  private static final int BACKUP_FORMAT_VERSION = 1
  private static final String MANIFEST_ENTRY = 'manifest.txt'
  private static final String DATABASE_SCRIPT_ENTRY = 'database/script.sql'
  private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern('yyyyMMdd-HHmmss')
  private static final int MAX_BACKUPS_TO_SCAN = 200
  private static final String ATTACHMENT_ROOT = 'attachments/'
  private static final String REPORT_ROOT = 'reports/'

  private final DatabaseService databaseService
  private final AttachmentService attachmentService
  private final ReportArchiveService reportArchiveService
  private final AuditLogService auditLogService
  private final MigrationService migrationService
  private final ReportIntegrityService reportIntegrityService

  BackupService() {
    this(
        DatabaseService.instance,
        new AttachmentService(DatabaseService.instance),
        new ReportArchiveService(DatabaseService.instance),
        new AuditLogService(DatabaseService.instance),
        new MigrationService(DatabaseService.instance),
        new ReportIntegrityService()
    )
  }

  BackupService(
      DatabaseService databaseService,
      AttachmentService attachmentService,
      ReportArchiveService reportArchiveService,
      AuditLogService auditLogService,
      MigrationService migrationService,
      ReportIntegrityService reportIntegrityService
  ) {
    this.databaseService = databaseService
    this.attachmentService = attachmentService
    this.reportArchiveService = reportArchiveService
    this.auditLogService = auditLogService
    this.migrationService = migrationService
    this.reportIntegrityService = reportIntegrityService
  }

  BackupResult createBackup(Path targetPath = null) {
    reportIntegrityService.ensureOperationAllowed('Backup')
    AppPaths.ensureDirectoryStructure()
    Path safeTarget = normalizeBackupTarget(targetPath)
    Path tempScript = Files.createTempFile('alipsa-accounting-backup-', '.sql')
    try {
      writeDatabaseScript(tempScript)
      byte[] scriptBytes = Files.readAllBytes(tempScript)
      List<AttachmentMetadata> attachments = attachmentService.listAllAttachments()
      List<ReportArchive> archives = reportArchiveService.listArchives(10_000)
      BackupManifest manifest = buildManifest(tempScript, scriptBytes, attachments, archives)
      Map<String, AttachmentMetadata> attachmentsByPath = indexAttachments(attachments)
      Map<String, ReportArchive> reportsByPath = indexReports(archives)
      Files.createDirectories(safeTarget.parent)
      ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(safeTarget), StandardCharsets.UTF_8)
      zip.withCloseable {
        writeEntry(zip, DATABASE_SCRIPT_ENTRY, scriptBytes)
        writeEntry(zip, MANIFEST_ENTRY, renderManifest(manifest).getBytes(StandardCharsets.UTF_8))
        writeAttachments(zip, manifest, attachmentsByPath)
        writeReports(zip, manifest, reportsByPath)
      }
      BackupSummary summary = new BackupSummary(
          safeTarget,
          manifest.createdAt,
          manifest.schemaVersion,
          manifest.files.count { BackupFileEntry file -> file.section == 'ATTACHMENT' } as int,
          manifest.files.count { BackupFileEntry file -> file.section == 'REPORT' } as int,
          sha256(safeTarget)
      )
      auditLogService.logBackup(
          "Backup skapad: ${safeTarget.fileName}",
          [
              "path=${safeTarget.toAbsolutePath()}",
              "schemaVersion=${summary.schemaVersion}",
              "attachments=${summary.attachmentCount}",
              "reports=${summary.reportCount}",
              "checksum=${summary.checksumSha256}"
          ].join('\n')
      )
      new BackupResult(summary, [])
    } finally {
      Files.deleteIfExists(tempScript)
    }
  }

  RestoreResult restoreBackup(Path backupPath, Path targetHome = AppPaths.applicationHome()) {
    Path safeBackup = requireBackupFile(backupPath)
    BackupManifest manifest = verifyBackup(safeBackup)
    Path safeHome = targetHome.toAbsolutePath().normalize()
    AppPaths.ensureDirectoryStructure(safeHome)
    Path tempDir = Files.createTempDirectory('alipsa-accounting-restore-')
    try {
      extractBackup(safeBackup, manifest, tempDir)
      clearDirectory(AppPaths.dataDirectory(safeHome))
      clearDirectory(AppPaths.attachmentsDirectory(safeHome))
      clearDirectory(AppPaths.reportsDirectory(safeHome))

      Files.createDirectories(AppPaths.dataDirectory(safeHome))
      Files.createDirectories(AppPaths.attachmentsDirectory(safeHome))
      Files.createDirectories(AppPaths.reportsDirectory(safeHome))
      restoreFiles(tempDir, safeHome)
      restoreDatabase(tempDir.resolve(DATABASE_SCRIPT_ENTRY), safeHome)

      if (safeHome == AppPaths.applicationHome()) {
        new AuditLogService().logRestore(
            "Backup återställd: ${safeBackup.fileName}",
            "path=${safeBackup.toAbsolutePath()}\nschemaVersion=${manifest.schemaVersion}"
        )
      }
      new RestoreResult(
          safeBackup,
          safeHome,
          manifest.files.count { BackupFileEntry file -> file.section == 'ATTACHMENT' } as int,
          manifest.files.count { BackupFileEntry file -> file.section == 'REPORT' } as int,
          manifest.schemaVersion
      )
    } finally {
      deleteRecursively(tempDir)
    }
  }

  BackupManifest verifyBackup(Path backupPath) {
    Path safeBackup = requireBackupFile(backupPath)
    ZipFile zipFile = new ZipFile(safeBackup.toFile(), StandardCharsets.UTF_8)
    zipFile.withCloseable { ZipFile zip ->
      BackupManifest manifest = readManifest(zip)
      verifyDatabaseScript(zip, manifest)
      manifest.files.each { BackupFileEntry file ->
        validateManifestEntryPath(file)
        ZipEntry zipEntry = zip.getEntry(file.relativePath)
        if (zipEntry == null) {
          throw new IllegalArgumentException("Backupen saknar ${file.relativePath}.")
        }
        byte[] content = zip.getInputStream(zipEntry).readAllBytes()
        if (sha256(content) != file.checksumSha256) {
          throw new IllegalArgumentException("Checksumma stämmer inte för ${file.relativePath}.")
        }
      }
      manifest
    }
  }

  List<BackupSummary> listBackups(int limit = 20) {
    int safeLimit = Math.max(1, limit)
    Path backupRoot = AppPaths.backupsDirectory()
    if (!Files.isDirectory(backupRoot)) {
      return []
    }
    Files.list(backupRoot).withCloseable { stream ->
      stream.filter { Path path -> Files.isRegularFile(path) && path.fileName.toString().toLowerCase(Locale.ROOT).endsWith('.zip') }
          .sorted { Path left, Path right -> Files.getLastModifiedTime(right) <=> Files.getLastModifiedTime(left) }
          .limit(Math.min(MAX_BACKUPS_TO_SCAN, safeLimit) as long)
          .collect { Path path ->
            try {
              BackupManifest manifest = readManifest(path)
              new BackupSummary(
                  path,
                  manifest.createdAt,
                  manifest.schemaVersion,
                  manifest.files.count { BackupFileEntry file -> file.section == 'ATTACHMENT' } as int,
                  manifest.files.count { BackupFileEntry file -> file.section == 'REPORT' } as int,
                  null
              )
            } catch (Exception ignored) {
              null
            }
          }.findAll { BackupSummary summary -> summary != null } as List<BackupSummary>
    }
  }

  private BackupManifest buildManifest(
      Path scriptPath,
      byte[] scriptBytes,
      List<AttachmentMetadata> attachments,
      List<ReportArchive> archives
  ) {
    List<BackupFileEntry> files = []
    attachments.each { AttachmentMetadata attachment ->
      Path source = attachmentService.resolveStoredPath(attachment)
      files << new BackupFileEntry(
          'ATTACHMENT',
          "${ATTACHMENT_ROOT}${attachment.storagePath}",
          sha256(source),
          Files.size(source)
      )
    }
    archives.each { ReportArchive archive ->
      Path source = reportArchiveService.resolveStoredPath(archive)
      files << new BackupFileEntry(
          'REPORT',
          "${REPORT_ROOT}${archive.storagePath}",
          sha256(source),
          Files.size(source)
      )
    }
    new BackupManifest(
        BACKUP_FORMAT_VERSION,
        LocalDateTime.now(),
        migrationService.currentSchemaVersion(),
        sha256(scriptBytes),
        Files.size(scriptPath),
        files
    )
  }

  private void writeDatabaseScript(Path scriptPath) {
    databaseService.withSql { Sql sql ->
      Connection connection = sql.connection
      Script.process(connection, scriptPath.toString(), '', '')
    }
  }

  private void writeAttachments(
      ZipOutputStream zip,
      BackupManifest manifest,
      Map<String, AttachmentMetadata> attachmentsByPath
  ) {
    manifest.files.findAll { BackupFileEntry file -> file.section == 'ATTACHMENT' }.each { BackupFileEntry file ->
      AttachmentMetadata attachment = attachmentsByPath.get(file.relativePath)
      if (attachment == null) {
        throw new IllegalStateException("Bilagan saknas i databasen för ${file.relativePath}.")
      }
      Path source = attachmentService.resolveStoredPath(attachment)
      writeEntry(zip, file.relativePath, Files.readAllBytes(source))
    }
  }

  private void writeReports(
      ZipOutputStream zip,
      BackupManifest manifest,
      Map<String, ReportArchive> reportsByPath
  ) {
    manifest.files.findAll { BackupFileEntry file -> file.section == 'REPORT' }.each { BackupFileEntry file ->
      ReportArchive archive = reportsByPath.get(file.relativePath)
      if (archive == null) {
        throw new IllegalStateException("Rapportarkivet saknas i databasen för ${file.relativePath}.")
      }
      Path source = reportArchiveService.resolveStoredPath(archive)
      writeEntry(zip, file.relativePath, Files.readAllBytes(source))
    }
  }

  private static void writeEntry(ZipOutputStream zip, String name, byte[] content) {
    ZipEntry entry = new ZipEntry(name)
    zip.putNextEntry(entry)
    zip.write(content)
    zip.closeEntry()
  }

  private static String renderManifest(BackupManifest manifest) {
    List<String> lines = [
        "formatVersion=${manifest.formatVersion}".toString(),
        "createdAt=${manifest.createdAt}".toString(),
        "schemaVersion=${manifest.schemaVersion}".toString(),
        "databasePath=${DATABASE_SCRIPT_ENTRY}".toString(),
        "databaseChecksumSha256=${manifest.databaseChecksumSha256}".toString(),
        "databaseSizeBytes=${manifest.databaseSizeBytes}".toString()
    ]
    manifest.files.each { BackupFileEntry file ->
      lines << "FILE\t${file.section}\t${file.relativePath}\t${file.checksumSha256}\t${file.sizeBytes}".toString()
    }
    lines.join('\n')
  }

  private static BackupManifest parseManifest(String content) {
    int formatVersion = 0
    LocalDateTime createdAt = null
    int schemaVersion = 0
    String databaseChecksum = null
    long databaseSize = 0L
    List<BackupFileEntry> files = []
    content.eachLine { String line ->
      if (!line?.trim()) {
        return
      }
      if (line.startsWith('FILE\t')) {
        String[] parts = line.split('\t')
        if (parts.length != 5) {
          throw new IllegalArgumentException("Ogiltig manifestrad: ${line}")
        }
        files << new BackupFileEntry(parts[1], parts[2], parts[3], Long.parseLong(parts[4]))
        return
      }
      int separator = line.indexOf('=')
      if (separator <= 0) {
        return
      }
      String key = line.substring(0, separator)
      String value = line.substring(separator + 1)
      switch (key) {
        case 'formatVersion':
          formatVersion = Integer.parseInt(value)
          break
        case 'createdAt':
          createdAt = LocalDateTime.parse(value)
          break
        case 'schemaVersion':
          schemaVersion = Integer.parseInt(value)
          break
        case 'databaseChecksumSha256':
          databaseChecksum = value
          break
        case 'databaseSizeBytes':
          databaseSize = Long.parseLong(value)
          break
        default:
          break
      }
    }
    new BackupManifest(formatVersion, createdAt, schemaVersion, databaseChecksum, databaseSize, files)
  }

  private static void verifyDatabaseScript(ZipFile zip, BackupManifest manifest) {
    ZipEntry script = zip.getEntry(DATABASE_SCRIPT_ENTRY)
    if (script == null) {
      throw new IllegalArgumentException('Backupen saknar databaseskript.')
    }
    byte[] content = zip.getInputStream(script).readAllBytes()
    if (sha256(content) != manifest.databaseChecksumSha256) {
      throw new IllegalArgumentException('Checksumma stämmer inte för databaseskriptet.')
    }
  }

  private static void extractBackup(Path backupPath, BackupManifest manifest, Path tempDir) {
    ZipFile zip = new ZipFile(backupPath.toFile(), StandardCharsets.UTF_8)
    zip.withCloseable { ZipFile zipFile ->
      ([DATABASE_SCRIPT_ENTRY] + manifest.files.collect { BackupFileEntry file -> file.relativePath }).each { String entryName ->
        ZipEntry entry = zipFile.getEntry(entryName)
        if (entry == null) {
          throw new IllegalArgumentException("Backupen saknar ${entryName}.")
        }
        Path target = resolveContainedPath(tempDir, entryName)
        Files.createDirectories(target.parent)
        Files.copy(zipFile.getInputStream(entry), target, StandardCopyOption.REPLACE_EXISTING)
      }
    }
  }

  private static void restoreFiles(Path tempDir, Path targetHome) {
    Path extractedAttachments = tempDir.resolve('attachments')
    Path extractedReports = tempDir.resolve('reports')
    if (Files.isDirectory(extractedAttachments)) {
      copyTree(extractedAttachments, AppPaths.attachmentsDirectory(targetHome))
    }
    if (Files.isDirectory(extractedReports)) {
      copyTree(extractedReports, AppPaths.reportsDirectory(targetHome))
    }
  }

  private static void restoreDatabase(Path scriptPath, Path targetHome) {
    RunScript.execute(
        DatabaseService.embeddedDatabaseUrl(targetHome),
        DatabaseService.USERNAME,
        DatabaseService.PASSWORD,
        scriptPath.toString(),
        StandardCharsets.UTF_8,
        false
    )
  }

  private static void copyTree(Path sourceRoot, Path targetRoot) {
    Files.walk(sourceRoot).withCloseable { stream ->
      stream.forEach { Path source ->
        Path relative = sourceRoot.relativize(source)
        Path target = targetRoot.resolve(relative)
        if (Files.isDirectory(source)) {
          Files.createDirectories(target)
        } else {
          Files.createDirectories(target.parent)
          Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
      }
    }
  }

  private static void clearDirectory(Path directory) {
    if (!Files.exists(directory)) {
      return
    }
    Files.list(directory).withCloseable { stream ->
      stream.forEach { Path child ->
        deleteRecursively(child)
      }
    }
  }

  private static void deleteRecursively(Path path) {
    if (path == null || !Files.exists(path)) {
      return
    }
    Files.walk(path).sorted(Comparator.reverseOrder()).forEach { Path child ->
      Files.deleteIfExists(child)
    }
  }

  private static Path normalizeBackupTarget(Path targetPath) {
    if (targetPath == null) {
      return AppPaths.backupsDirectory().resolve("backup-${LocalDateTime.now().format(FILE_TIMESTAMP)}.zip")
    }
    Path normalized = targetPath.toAbsolutePath().normalize()
    if (normalized.parent == null) {
      throw new IllegalArgumentException("Ogiltig backupfil: ${normalized}")
    }
    normalized
  }

  private static Path requireBackupFile(Path backupPath) {
    if (backupPath == null) {
      throw new IllegalArgumentException('En backupfil måste väljas.')
    }
    Path normalized = backupPath.toAbsolutePath().normalize()
    if (!Files.isRegularFile(normalized)) {
      throw new IllegalArgumentException("Backupfilen hittades inte: ${normalized}")
    }
    normalized
  }

  private static String sha256(Path file) {
    MessageDigest digest = MessageDigest.getInstance('SHA-256')
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

  private static String sha256(byte[] content) {
    MessageDigest digest = MessageDigest.getInstance('SHA-256')
    HexFormat.of().formatHex(digest.digest(content))
  }

  private static BackupManifest readManifest(Path backupPath) {
    Path safeBackup = requireBackupFile(backupPath)
    ZipFile zipFile = new ZipFile(safeBackup.toFile(), StandardCharsets.UTF_8)
    zipFile.withCloseable { ZipFile zip ->
      readManifest(zip)
    }
  }

  private static BackupManifest readManifest(ZipFile zip) {
    ZipEntry manifestEntry = zip.getEntry(MANIFEST_ENTRY)
    if (manifestEntry == null) {
      throw new IllegalArgumentException('Backupen saknar manifest.txt.')
    }
    BackupManifest manifest = parseManifest(new String(zip.getInputStream(manifestEntry).readAllBytes(), StandardCharsets.UTF_8))
    manifest.files.each { BackupFileEntry file ->
      validateManifestEntryPath(file)
    }
    manifest
  }

  private static Map<String, AttachmentMetadata> indexAttachments(List<AttachmentMetadata> attachments) {
    Map<String, AttachmentMetadata> index = [:]
    attachments.each { AttachmentMetadata attachment ->
      index.put("${ATTACHMENT_ROOT}${attachment.storagePath}".toString(), attachment)
    }
    index
  }

  private static Map<String, ReportArchive> indexReports(List<ReportArchive> archives) {
    Map<String, ReportArchive> index = [:]
    archives.each { ReportArchive archive ->
      index.put("${REPORT_ROOT}${archive.storagePath}".toString(), archive)
    }
    index
  }

  private static void validateManifestEntryPath(BackupFileEntry file) {
    if (!(file.section in ['ATTACHMENT', 'REPORT'])) {
      throw new IllegalArgumentException("Backupen innehåller en ogiltig sektion: ${file.section}")
    }
    String expectedPrefix = file.section == 'ATTACHMENT' ? ATTACHMENT_ROOT : REPORT_ROOT
    if (!(file.relativePath?.startsWith(expectedPrefix))) {
      throw new IllegalArgumentException("Backupen innehåller en otillåten sökväg: ${file.relativePath}")
    }
    resolveContainedPath(Path.of('/tmp/alipsa-accounting-validate'), file.relativePath)
  }

  private static Path resolveContainedPath(Path root, String entryName) {
    if (!entryName?.trim()) {
      throw new IllegalArgumentException('Backupen innehåller en tom sökväg.')
    }
    Path resolved = root.resolve(entryName).normalize()
    if (!resolved.startsWith(root)) {
      throw new IllegalArgumentException("Backupen innehåller en otillåten sökväg: ${entryName}")
    }
    resolved
  }
}
