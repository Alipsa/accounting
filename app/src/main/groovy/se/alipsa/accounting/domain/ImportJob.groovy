package se.alipsa.accounting.domain

import groovy.transform.Canonical
import groovy.transform.CompileStatic

import java.time.LocalDateTime

/**
 * Metadata and outcome for one SIE import attempt.
 */
@Canonical
@CompileStatic
final class ImportJob {

  Long id
  String fileName
  String checksumSha256
  Long fiscalYearId
  ImportJobStatus status
  String summary
  String errorLog
  LocalDateTime startedAt
  LocalDateTime completedAt
}
