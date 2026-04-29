package se.alipsa.accounting.service

import groovy.transform.Canonical

import java.nio.file.Path

/**
 * Report produced by AttachmentService.recoverOnStartup() summarizing
 * recovered, failed, and orphaned attachment state.
 */
@Canonical
final class AttachmentRecoveryReport {

  int activated
  int failed
  int deletionsDone
  List<Path> orphanFiles
}
