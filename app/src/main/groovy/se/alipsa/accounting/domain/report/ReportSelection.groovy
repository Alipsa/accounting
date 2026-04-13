package se.alipsa.accounting.domain.report

import groovy.transform.Canonical

import java.time.LocalDate

/**
 * User supplied report filter that resolves to an effective fiscal-year date range.
 */
@Canonical
final class ReportSelection {

  ReportType reportType
  Long fiscalYearId
  Long accountingPeriodId
  LocalDate startDate
  LocalDate endDate
}
