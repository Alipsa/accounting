package se.alipsa.accounting.service

import groovy.transform.CompileStatic

import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Guards deletion of accounting records, attachments and archives within the retention window.
 */
@CompileStatic
final class RetentionPolicyService {

  static final int RETENTION_YEARS = 7

  private final Clock clock

  RetentionPolicyService() {
    this(Clock.systemDefaultZone())
  }

  RetentionPolicyService(Clock clock) {
    this.clock = clock
  }

  void ensureDeletionAllowed(LocalDateTime createdAt, String label) {
    if (createdAt == null) {
      throw new IllegalArgumentException("Kan inte avgöra bevarandetid för ${label}.")
    }
    LocalDate threshold = LocalDate.now(clock).minusYears(RETENTION_YEARS)
    if (createdAt.toLocalDate().isAfter(threshold)) {
      throw new IllegalStateException("${label} får inte rensas före sju års bevarandetid.")
    }
  }

  void ensureDeletionAllowed(LocalDate endDate, String label) {
    if (endDate == null) {
      throw new IllegalArgumentException("Kan inte avgöra bevarandetid för ${label}.")
    }
    LocalDate threshold = LocalDate.now(clock).minusYears(RETENTION_YEARS)
    if (endDate.isAfter(threshold)) {
      throw new IllegalStateException("${label} får inte rensas före sju års bevarandetid.")
    }
  }
}
