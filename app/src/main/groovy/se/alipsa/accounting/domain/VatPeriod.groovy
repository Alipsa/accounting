package se.alipsa.accounting.domain

import groovy.transform.Canonical

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * VAT reporting period derived from accounting periods.
 */
@Canonical
final class VatPeriod {

  Long id
  Long fiscalYearId
  int periodIndex
  String periodName
  LocalDate startDate
  LocalDate endDate
  String status
  String reportHash
  LocalDateTime reportedAt
  LocalDateTime lockedAt
  Long transferVoucherId

  boolean isReported() {
    status in ['REPORTED', 'LOCKED']
  }

  boolean isLocked() {
    status == 'LOCKED'
  }
}
