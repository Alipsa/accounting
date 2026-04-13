package se.alipsa.accounting.domain

import groovy.transform.Canonical

import java.time.LocalDateTime

/**
 * Immutable audit trail entry for business-critical actions.
 */
@Canonical
final class AuditLogEntry {

  Long id
  String eventType
  Long voucherId
  Long attachmentId
  Long fiscalYearId
  Long accountingPeriodId
  Long vatPeriodId
  String actor
  String summary
  String details
  String previousHash
  String entryHash
  LocalDateTime createdAt
}
