package se.alipsa.accounting.service

import groovy.sql.GroovyRowResult
import groovy.sql.Sql

import se.alipsa.accounting.domain.AttachmentMetadata
import se.alipsa.accounting.support.AppPaths

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.logging.Logger

/**
 * Stores voucher attachments on disk and keeps metadata plus checksums in the database.
 */
final class AttachmentService {

  private static final String DEFAULT_CONTENT_TYPE = 'application/octet-stream'
  private static final Logger log = Logger.getLogger(AttachmentService.name)

  private final DatabaseService databaseService
  private final AuditLogService auditLogService
  private final RetentionPolicyService retentionPolicyService

  AttachmentService() {
    this(DatabaseService.instance)
  }

  AttachmentService(DatabaseService databaseService) {
    this(databaseService, new AuditLogService(databaseService), new RetentionPolicyService())
  }

  AttachmentService(DatabaseService databaseService, AuditLogService auditLogService) {
    this(databaseService, auditLogService, new RetentionPolicyService())
  }

  AttachmentService(
      DatabaseService databaseService,
      AuditLogService auditLogService,
      RetentionPolicyService retentionPolicyService
  ) {
    this.databaseService = databaseService
    this.auditLogService = auditLogService
    this.retentionPolicyService = retentionPolicyService
  }

  AttachmentMetadata addAttachment(long voucherId, Path sourceFile) {
    Path safeSource = requireSourceFile(sourceFile)
    AppPaths.ensureDirectoryStructure()
    String checksum = calculateChecksum(safeSource)
    String storagePath = buildStoragePath(voucherId, safeSource.fileName.toString())
    Path targetPath = resolveStoragePath(storagePath)
    Files.createDirectories(targetPath.parent)

    long attachmentId = databaseService.withTransaction { Sql sql ->
      requireVoucher(sql, voucherId)
      LocalDateTime createdAt = currentDatabaseTimestamp(sql)
      List<List<Object>> keys = sql.executeInsert('''
          insert into attachment (
              voucher_id,
              original_file_name,
              content_type,
              storage_path,
              checksum_sha256,
              file_size,
              created_at,
              status
          ) values (?, ?, ?, ?, ?, ?, ?, ?)
      ''', [
          voucherId,
          safeSource.fileName.toString(),
          detectContentType(safeSource),
          storagePath,
          checksum,
          Files.size(safeSource),
          Timestamp.valueOf(createdAt),
          'PENDING'
      ])
      ((Number) keys.first().first()).longValue()
    }

    try {
      Files.copy(safeSource, targetPath, StandardCopyOption.REPLACE_EXISTING)
      String storedChecksum = calculateChecksum(targetPath)
      if (checksum != storedChecksum) {
        tryDeleteQuietly(targetPath)
        databaseService.withTransaction { Sql sql ->
          sql.executeUpdate('update attachment set status = ? where id = ?', ['FAILED', attachmentId])
        }
        throw new IllegalStateException("Checksum mismatch after copying attachment: ${safeSource.fileName}")
      }
    } catch (IOException exception) {
      tryDeleteQuietly(targetPath)
      databaseService.withTransaction { Sql sql ->
        sql.executeUpdate('update attachment set status = ? where id = ?', ['FAILED', attachmentId])
      }
      throw exception
    }

    databaseService.withTransaction { Sql sql ->
      sql.executeUpdate('update attachment set status = ? where id = ?', ['ACTIVE', attachmentId])
      AttachmentMetadata attachment = findAttachment(sql, attachmentId)
      auditLogService.recordAttachmentAdded(sql, attachment)
      attachment
    }
  }

  List<AttachmentMetadata> listAttachments(long voucherId) {
    databaseService.withSql { Sql sql ->
      sql.rows('''
          select id,
                 voucher_id as voucherId,
                 original_file_name as originalFileName,
                 content_type as contentType,
                 storage_path as storagePath,
                 checksum_sha256 as checksumSha256,
                 file_size as fileSize,
                 created_at as createdAt,
                 status
            from attachment
           where voucher_id = ?
             and status = 'ACTIVE'
           order by created_at desc, id desc
      ''', [voucherId]).collect { GroovyRowResult row ->
        mapAttachment(row)
      }
    }
  }

  List<AttachmentMetadata> listAllAttachments(long companyId) {
    CompanyService.requireValidCompanyId(companyId)
    databaseService.withSql { Sql sql ->
      sql.rows('''
          select id,
                 voucher_id as voucherId,
                 original_file_name as originalFileName,
                 content_type as contentType,
                 storage_path as storagePath,
                 checksum_sha256 as checksumSha256,
                 file_size as fileSize,
                 created_at as createdAt,
                 status
            from attachment
           where company_id = ?
             and status = 'ACTIVE'
           order by id
      ''', [companyId]).collect { GroovyRowResult row ->
        mapAttachment(row)
      }
    }
  }

  List<AttachmentMetadata> listAllAttachments() {
    databaseService.withSql { Sql sql ->
      sql.rows('''
          select id,
                 voucher_id as voucherId,
                 original_file_name as originalFileName,
                 content_type as contentType,
                 storage_path as storagePath,
                 checksum_sha256 as checksumSha256,
                 file_size as fileSize,
                 created_at as createdAt,
                 status
            from attachment
           where status = 'ACTIVE'
           order by id
      ''').collect { GroovyRowResult row ->
        mapAttachment(row)
      }
    }
  }

  AttachmentMetadata findAttachment(long attachmentId) {
    databaseService.withSql { Sql sql ->
      findAttachment(sql, attachmentId)
    }
  }

  byte[] readAttachment(long attachmentId) {
    AttachmentMetadata attachment = requireActiveAttachment(attachmentId)
    Files.readAllBytes(resolveStoragePath(attachment.storagePath))
  }

  boolean verifyAttachment(long attachmentId) {
    AttachmentMetadata attachment = requireAttachment(attachmentId)
    verifyAttachment(attachment)
  }

  void deleteAttachment(long attachmentId) {
    AttachmentMetadata attachment = requireActiveAttachment(attachmentId)
    retentionPolicyService.ensureDeletionAllowed(attachment.createdAt, "Bilaga ${attachment.originalFileName}")
    Path targetPath = resolveStoragePath(attachment.storagePath)
    databaseService.withTransaction { Sql sql ->
      int updated = sql.executeUpdate(
          'update attachment set status = ? where id = ?',
          ['PENDING_DELETE', attachmentId]
      )
      if (updated != 1) {
        throw new IllegalStateException("Bilagan kunde inte markeras för borttagning: ${attachmentId}")
      }
    }
    Files.deleteIfExists(targetPath)
    databaseService.withTransaction { Sql sql ->
      int updated = sql.executeUpdate(
          'update attachment set status = ? where id = ?',
          ['DELETED', attachmentId]
      )
      if (updated != 1) {
        throw new IllegalStateException("Bilagan kunde inte slutföras som borttagen: ${attachmentId}")
      }
    }
  }

  List<AttachmentMetadata> findIntegrityFailures(long companyId) {
    CompanyService.requireValidCompanyId(companyId)
    databaseService.withSql { Sql sql ->
      List<AttachmentMetadata> failed = sql.rows('''
          select id,
                 voucher_id as voucherId,
                 original_file_name as originalFileName,
                 content_type as contentType,
                 storage_path as storagePath,
                 checksum_sha256 as checksumSha256,
                 file_size as fileSize,
                 created_at as createdAt,
                 status
            from attachment
           where company_id = ?
             and status = 'FAILED'
           order by id
      ''', [companyId]).collect { GroovyRowResult row ->
        mapAttachment(row)
      }
      List<AttachmentMetadata> brokenActive = sql.rows('''
          select id,
                 voucher_id as voucherId,
                 original_file_name as originalFileName,
                 content_type as contentType,
                 storage_path as storagePath,
                 checksum_sha256 as checksumSha256,
                 file_size as fileSize,
                 created_at as createdAt,
                 status
            from attachment
           where company_id = ?
             and status = 'ACTIVE'
           order by id
      ''', [companyId]).collect { GroovyRowResult row ->
        mapAttachment(row)
      }.findAll { AttachmentMetadata attachment ->
        !verifyAttachment(attachment)
      }
      failed + brokenActive
    }
  }

  List<AttachmentMetadata> findAllIntegrityFailures() {
    databaseService.withSql { Sql sql ->
      List<AttachmentMetadata> failed = sql.rows('''
          select id,
                 voucher_id as voucherId,
                 original_file_name as originalFileName,
                 content_type as contentType,
                 storage_path as storagePath,
                 checksum_sha256 as checksumSha256,
                 file_size as fileSize,
                 created_at as createdAt,
                 status
            from attachment
           where status = 'FAILED'
           order by id
      ''').collect { GroovyRowResult row ->
        mapAttachment(row)
      }
      List<AttachmentMetadata> brokenActive = sql.rows('''
          select id,
                 voucher_id as voucherId,
                 original_file_name as originalFileName,
                 content_type as contentType,
                 storage_path as storagePath,
                 checksum_sha256 as checksumSha256,
                 file_size as fileSize,
                 created_at as createdAt,
                 status
            from attachment
           where status = 'ACTIVE'
           order by id
      ''').collect { GroovyRowResult row ->
        mapAttachment(row)
      }.findAll { AttachmentMetadata attachment ->
        !verifyAttachment(attachment)
      }
      failed + brokenActive
    }
  }

  Path resolveStoredPath(AttachmentMetadata attachment) {
    resolveStoragePath(attachment.storagePath)
  }

  AttachmentRecoveryReport recoverOnStartup() {
    int activated = 0
    int failed = 0
    int deletionsDone = 0

    databaseService.withTransaction { Sql sql ->
      List<AttachmentMetadata> pending = sql.rows('''
          select id,
                 voucher_id as voucherId,
                 original_file_name as originalFileName,
                 content_type as contentType,
                 storage_path as storagePath,
                 checksum_sha256 as checksumSha256,
                 file_size as fileSize,
                 created_at as createdAt,
                 status
            from attachment
           where status = 'PENDING'
           order by id
      ''').collect { GroovyRowResult row ->
        mapAttachment(row)
      }

      pending.each { AttachmentMetadata attachment ->
        Path path = resolveStoragePath(attachment.storagePath)
        if (!Files.isRegularFile(path)) {
          sql.executeUpdate('update attachment set status = ? where id = ?', ['FAILED', attachment.id])
          log.warning("Attachment ${attachment.id} is PENDING but file is missing; marked FAILED.")
          failed++
          return
        }
        String checksum = calculateChecksum(path)
        if (checksum == attachment.checksumSha256) {
          sql.executeUpdate('update attachment set status = ? where id = ?', ['ACTIVE', attachment.id])
          AttachmentMetadata recovered = findAttachment(sql, attachment.id)
          auditLogService.recordAttachmentRecovered(sql, recovered)
          log.info("Recovered attachment ${attachment.id} to ACTIVE.")
          activated++
        } else {
          tryDeleteQuietly(path)
          sql.executeUpdate('update attachment set status = ? where id = ?', ['FAILED', attachment.id])
          log.warning("Attachment ${attachment.id} has checksum mismatch; marked FAILED.")
          failed++
        }
      }

      List<AttachmentMetadata> pendingDelete = sql.rows('''
          select id,
                 voucher_id as voucherId,
                 original_file_name as originalFileName,
                 content_type as contentType,
                 storage_path as storagePath,
                 checksum_sha256 as checksumSha256,
                 file_size as fileSize,
                 created_at as createdAt,
                 status
            from attachment
           where status = 'PENDING_DELETE'
           order by id
      ''').collect { GroovyRowResult row ->
        mapAttachment(row)
      }

      pendingDelete.each { AttachmentMetadata attachment ->
        Path path = resolveStoragePath(attachment.storagePath)
        tryDeleteQuietly(path)
        if (!Files.isRegularFile(path)) {
          sql.executeUpdate('update attachment set status = ? where id = ?', ['DELETED', attachment.id])
          deletionsDone++
        } else {
          log.warning("Attachment ${attachment.id} file could not be deleted during recovery; leaving PENDING_DELETE.")
        }
      }
    }

    List<Path> orphanFiles = findOrphanFiles()
    new AttachmentRecoveryReport(activated, failed, deletionsDone, orphanFiles)
  }

  private List<Path> findOrphanFiles() {
    Path root = AppPaths.attachmentsDirectory().toAbsolutePath().normalize()
    if (!Files.isDirectory(root)) {
      return []
    }

    Set<String> knownPaths = databaseService.withSql { Sql sql ->
      sql.rows('''
          select storage_path as storagePath
            from attachment
           where status != 'DELETED'
      ''').collect { GroovyRowResult row ->
        ((String) row.get('storagePath')).replace('\\', '/')
      } as Set
    }

    List<Path> orphans = []
    Files.walk(root).each { Path path ->
      if (Files.isRegularFile(path)) {
        String relative = root.relativize(path).toString().replace('\\', '/')
        if (!knownPaths.contains(relative)) {
          orphans << path
        }
      }
    }
    orphans
  }

  private static void tryDeleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path)
    } catch (IOException ignored) {
      // best-effort cleanup
    }
  }

  private AttachmentMetadata requireAttachment(long attachmentId) {
    AttachmentMetadata attachment = findAttachment(attachmentId)
    if (attachment == null) {
      throw new IllegalArgumentException("Unknown attachment: ${attachmentId}")
    }
    attachment
  }

  private AttachmentMetadata requireActiveAttachment(long attachmentId) {
    AttachmentMetadata attachment = requireAttachment(attachmentId)
    if (attachment.status != 'ACTIVE') {
      throw new IllegalArgumentException(
          "Attachment ${attachmentId} is not active (status=${attachment.status})"
      )
    }
    attachment
  }

  private boolean verifyAttachment(AttachmentMetadata attachment) {
    Path path
    try {
      path = resolveStoragePath(attachment.storagePath)
    } catch (SecurityException ignored) {
      return false
    }
    if (!Files.isRegularFile(path)) {
      return false
    }
    String checksum = calculateChecksum(path)
    checksum == attachment.checksumSha256 && Files.size(path) == attachment.fileSize
  }

  private static AttachmentMetadata findAttachment(Sql sql, long attachmentId) {
    GroovyRowResult row = sql.firstRow('''
        select id,
               voucher_id as voucherId,
               original_file_name as originalFileName,
               content_type as contentType,
               storage_path as storagePath,
               checksum_sha256 as checksumSha256,
               file_size as fileSize,
               created_at as createdAt,
               status
          from attachment
         where id = ?
    ''', [attachmentId]) as GroovyRowResult
    row == null ? null : mapAttachment(row)
  }

  private static AttachmentMetadata mapAttachment(GroovyRowResult row) {
    new AttachmentMetadata(
        Long.valueOf(row.get('id').toString()),
        Long.valueOf(row.get('voucherId').toString()),
        row.get('originalFileName') as String,
        row.get('contentType') as String,
        row.get('storagePath') as String,
        row.get('checksumSha256') as String,
        Long.valueOf(row.get('fileSize').toString()),
        SqlValueMapper.toLocalDateTime(row.get('createdAt')),
        row.get('status') as String
    )
  }

  private static void requireVoucher(Sql sql, long voucherId) {
    GroovyRowResult row = sql.firstRow(
        'select count(*) as total from voucher where id = ?',
        [voucherId]
    ) as GroovyRowResult
    if (((Number) row.get('total')).intValue() != 1) {
      throw new IllegalArgumentException("Unknown voucher: ${voucherId}")
    }
  }

  private static LocalDateTime currentDatabaseTimestamp(Sql sql) {
    GroovyRowResult row = sql.firstRow('select current_timestamp as createdAt') as GroovyRowResult
    SqlValueMapper.toLocalDateTime(row.get('createdAt'))
  }

  private static Path requireSourceFile(Path sourceFile) {
    if (sourceFile == null || !Files.isRegularFile(sourceFile)) {
      throw new IllegalArgumentException('Attachment source file is required.')
    }
    sourceFile.toAbsolutePath().normalize()
  }

  private static String detectContentType(Path sourceFile) {
    Files.probeContentType(sourceFile) ?: DEFAULT_CONTENT_TYPE
  }

  private static String buildStoragePath(long voucherId, String originalFileName) {
    String sanitized = sanitizeFileName(originalFileName)
    "voucher-${voucherId}/${UUID.randomUUID()}-${sanitized}"
  }

  private static String sanitizeFileName(String originalFileName) {
    String safeName = originalFileName?.trim()
    if (!safeName) {
      return 'attachment.bin'
    }
    safeName.replaceAll(/[^A-Za-z0-9._-]/, '_')
  }

  private static Path resolveStoragePath(String storagePath) {
    Path root = AppPaths.attachmentsDirectory().toAbsolutePath().normalize()
    Path resolved = root.resolve(storagePath).normalize()
    if (!resolved.startsWith(root)) {
      throw new SecurityException("Bilagans lagringsväg ligger utanför bilagearkivet: ${storagePath}")
    }
    resolved
  }

  private static String calculateChecksum(Path file) {
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
}
