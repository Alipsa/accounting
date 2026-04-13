package se.alipsa.accounting.service

import groovy.transform.Canonical

import java.nio.file.Path
import java.time.LocalDateTime

@Canonical
final class BackupFileEntry {

  String section
  String relativePath
  String checksumSha256
  long sizeBytes
}

@Canonical
final class BackupManifest {

  int formatVersion
  LocalDateTime createdAt
  int schemaVersion
  String databaseChecksumSha256
  long databaseSizeBytes
  List<BackupFileEntry> files = []
}

@Canonical
final class BackupSummary {

  Path backupPath
  LocalDateTime createdAt
  int schemaVersion
  int attachmentCount
  int reportCount
  String checksumSha256
}

@Canonical
final class BackupResult {

  BackupSummary summary
  List<String> warnings = []
}

@Canonical
final class RestoreResult {

  Path backupPath
  Path restoredHome
  int restoredAttachmentCount
  int restoredReportCount
  int schemaVersion
}
