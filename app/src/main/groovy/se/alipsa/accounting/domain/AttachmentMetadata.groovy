package se.alipsa.accounting.domain

import groovy.transform.Canonical
import groovy.transform.CompileStatic

import java.time.LocalDateTime

/**
 * Metadata for a voucher attachment stored in the local attachment archive.
 */
@Canonical
@CompileStatic
final class AttachmentMetadata {

  Long id
  long voucherId
  String originalFileName
  String contentType
  String storagePath
  String checksumSha256
  long fileSize
  LocalDateTime createdAt
}
