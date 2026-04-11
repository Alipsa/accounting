package se.alipsa.accounting.domain.report

import groovy.transform.Canonical
import groovy.transform.CompileStatic

import java.time.LocalDate

/**
 * Unified report payload used by preview, CSV export and PDF rendering.
 */
@Canonical
@CompileStatic
final class ReportResult {

  ReportType reportType
  String title
  String selectionLabel
  Long fiscalYearId
  Long accountingPeriodId
  LocalDate startDate
  LocalDate endDate
  boolean csvSupported
  List<String> summaryLines
  List<String> tableHeaders
  List<List<String>> tableRows
  List<Long> rowVoucherIds
  Map<String, Object> templateModel
}
