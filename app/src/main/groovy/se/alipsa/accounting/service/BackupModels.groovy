package se.alipsa.accounting.service

import groovy.transform.Canonical
import groovy.transform.CompileStatic

import java.nio.file.Path
import java.time.LocalDateTime

@Canonical
@CompileStatic
final class BackupFileEntry {

  String section
  String relativePath
  String checksumSha256
  long sizeBytes
}

@Canonical
@CompileStatic
final class BackupManifest {

  int formatVersion
  LocalDateTime createdAt
  int schemaVersion
  String databaseChecksumSha256
  long databaseSizeBytes
  List<BackupFileEntry> files = []
}

@Canonical
@CompileStatic
final class BackupSummary {

  Path backupPath
  LocalDateTime createdAt
  int schemaVersion
  int attachmentCount
  int reportCount
  String checksumSha256
}

@Canonical
@CompileStatic
final class BackupResult {

  BackupSummary summary
  List<String> warnings = []
}

@Canonical
@CompileStatic
final class RestoreResult {

  Path backupPath
  Path restoredHome
  int restoredAttachmentCount
  int restoredReportCount
  int schemaVersion
}

@Canonical
@CompileStatic
final class StartupVerificationReport {

  LocalDateTime verifiedAt
  boolean ok
  List<String> errors = []
  List<String> warnings = []
}

@Canonical
@CompileStatic
final class SystemDiagnosticsSnapshot {

  Path applicationHome
  Path databaseFile
  int schemaVersion
  int expectedSchemaVersion
  StartupVerificationReport verificationReport
  BackupSummary latestBackup
  LocalDateTime latestSieExportAt
  String latestSieExportSummary
}
