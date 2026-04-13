package se.alipsa.accounting.domain

import groovy.transform.Canonical

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * One fiscal year period used for lock checks and monthly rollups.
 */
@Canonical
final class AccountingPeriod {

  Long id
  Long fiscalYearId
  int periodIndex
  String periodName
  LocalDate startDate
  LocalDate endDate
  boolean locked
  String lockReason
  LocalDateTime lockedAt
}
