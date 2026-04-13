package se.alipsa.accounting.service

import groovy.transform.Canonical
import groovy.transform.CompileStatic

import java.nio.file.Path
import java.time.LocalDateTime

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
