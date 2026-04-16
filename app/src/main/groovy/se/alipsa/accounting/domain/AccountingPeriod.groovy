package se.alipsa.accounting.domain

import groovy.transform.EqualsAndHashCode
import groovy.transform.TupleConstructor

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * One fiscal year period used for lock checks and monthly rollups.
 */
@TupleConstructor
@EqualsAndHashCode
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

  @Override
  String toString() {
    periodName
  }
}
