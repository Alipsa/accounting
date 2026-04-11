package se.alipsa.accounting.service

import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.transform.CompileStatic

import se.alipsa.accounting.domain.AttachmentMetadata
import se.alipsa.accounting.support.AppPaths

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.LocalDateTime

/**
 * Stores voucher attachments on disk and keeps metadata plus checksums in the database.
 */
@CompileStatic
final class AttachmentService {

  private static final String DEFAULT_CONTENT_TYPE = 'application/octet-stream'

  private final DatabaseService databaseService
  private final AuditLogService auditLogService

  AttachmentService() {
    this(DatabaseService.instance)
  }

  AttachmentService(DatabaseService databaseService) {
    this(databaseService, new AuditLogService(databaseService))
  }

  AttachmentService(DatabaseService databaseService, AuditLogService auditLogService) {
    this.databaseService = databaseService
    this.auditLogService = auditLogService
  }

  AttachmentMetadata addAttachment(long voucherId, Path sourceFile) {
    Path safeSource = requireSourceFile(sourceFile)
    AppPaths.ensureDirectoryStructure()
    String checksum = calculateChecksum(safeSource)
    String storagePath = buildStoragePath(voucherId, safeSource.fileName.toString())
    Path targetPath = resolveStoragePath(storagePath)
    Files.createDirectories(targetPath.parent)
    Files.copy(safeSource, targetPath, StandardCopyOption.REPLACE_EXISTING)
    String storedChecksum = calculateChecksum(targetPath)
    if (checksum != storedChecksum) {
      Files.deleteIfExists(targetPath)
      throw new IllegalStateException("Checksum mismatch after copying attachment: ${safeSource.fileName}")
    }

    try {
      databaseService.withTransaction { Sql sql ->
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
                created_at
            ) values (?, ?, ?, ?, ?, ?, ?)
        ''', [
            voucherId,
            safeSource.fileName.toString(),
            detectContentType(safeSource),
            storagePath,
            checksum,
            Files.size(targetPath),
            Timestamp.valueOf(createdAt)
        ])
        long attachmentId = ((Number) keys.first().first()).longValue()
        AttachmentMetadata attachment = findAttachment(sql, attachmentId)
        auditLogService.recordAttachmentAdded(sql, attachment)
        attachment
      }
    } catch (Throwable throwable) {
      Files.deleteIfExists(targetPath)
      throw throwable
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
                 created_at as createdAt
            from attachment
           where voucher_id = ?
           order by created_at desc, id desc
      ''', [voucherId]).collect { GroovyRowResult row ->
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
    AttachmentMetadata attachment = requireAttachment(attachmentId)
    Files.readAllBytes(resolveStoragePath(attachment.storagePath))
  }

  boolean verifyAttachment(long attachmentId) {
    AttachmentMetadata attachment = requireAttachment(attachmentId)
    verifyAttachment(attachment)
  }

  List<AttachmentMetadata> findIntegrityFailures() {
    databaseService.withSql { Sql sql ->
      sql.rows('''
          select id,
                 voucher_id as voucherId,
                 original_file_name as originalFileName,
                 content_type as contentType,
                 storage_path as storagePath,
                 checksum_sha256 as checksumSha256,
                 file_size as fileSize,
                 created_at as createdAt
            from attachment
           order by id
      ''').collect { GroovyRowResult row ->
        mapAttachment(row)
      }.findAll { AttachmentMetadata attachment ->
        !verifyAttachment(attachment)
      }
    }
  }

  Path resolveStoredPath(AttachmentMetadata attachment) {
    resolveStoragePath(attachment.storagePath)
  }

  private AttachmentMetadata requireAttachment(long attachmentId) {
    AttachmentMetadata attachment = findAttachment(attachmentId)
    if (attachment == null) {
      throw new IllegalArgumentException("Unknown attachment: ${attachmentId}")
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
               created_at as createdAt
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
        SqlValueMapper.toLocalDateTime(row.get('createdAt'))
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
