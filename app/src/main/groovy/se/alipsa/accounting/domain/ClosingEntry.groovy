package se.alipsa.accounting.domain

import groovy.transform.Canonical

import java.time.LocalDateTime

/**
 * Persisted trace entry for generated year-end closings and opening balances.
 */
@Canonical
final class ClosingEntry {

  Long id
  long fiscalYearId
  Long nextFiscalYearId
  Long voucherId
  String entryType
  String accountNumber
  String counterAccountNumber
  BigDecimal amount
  LocalDateTime createdAt
}
