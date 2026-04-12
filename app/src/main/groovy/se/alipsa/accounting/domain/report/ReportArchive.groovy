package se.alipsa.accounting.domain.report

import groovy.transform.Canonical
import groovy.transform.CompileStatic

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Metadata for one archived report artifact stored on disk.
 */
@Canonical
@CompileStatic
final class ReportArchive {

  Long id
  ReportType reportType
  String reportFormat
  Long fiscalYearId
  Long accountingPeriodId
  LocalDate startDate
  LocalDate endDate
  String fileName
  String storagePath
  String checksumSha256
  String parameters
  LocalDateTime createdAt
}
