package se.alipsa.accounting.service

import groovy.transform.Canonical

import java.nio.file.Path

/**
 * Report produced by ReportArchiveService.recoverOnStartup() summarizing
 * completed deletions and orphaned report-archive files.
 */
@Canonical
final class ReportArchiveRecoveryReport {

  int deletionsDone
  List<Path> orphanFiles
  List<String> warnings
}
