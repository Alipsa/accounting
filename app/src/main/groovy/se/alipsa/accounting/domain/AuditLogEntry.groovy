package se.alipsa.accounting.domain

import groovy.transform.Canonical
import groovy.transform.CompileStatic

import java.time.LocalDateTime

/**
 * Immutable audit trail entry for business-critical actions.
 */
@Canonical
@CompileStatic
final class AuditLogEntry {

  Long id
  String eventType
  Long voucherId
  Long attachmentId
  Long fiscalYearId
  Long accountingPeriodId
  String actor
  String summary
  String details
  String previousHash
  String entryHash
  LocalDateTime createdAt
}
