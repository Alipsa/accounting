package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertThrows

import org.junit.jupiter.api.Test

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class RetentionPolicyServiceTest {

  private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse('2030-01-01T00:00:00Z'), ZoneOffset.UTC)

  @Test
  void deletionInsideRetentionWindowIsRejected() {
    RetentionPolicyService service = new RetentionPolicyService(FIXED_CLOCK)

    assertThrows(IllegalStateException) {
      service.ensureDeletionAllowed(LocalDateTime.of(2028, 1, 1, 0, 0), 'Bilaga')
    }
  }

  @Test
  void deletionOutsideRetentionWindowIsAllowed() {
    RetentionPolicyService service = new RetentionPolicyService(FIXED_CLOCK)

    service.ensureDeletionAllowed(LocalDate.of(2022, 12, 31), 'Räkenskapsår')
  }
}
