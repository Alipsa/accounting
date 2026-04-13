package se.alipsa.accounting.service

import java.sql.Date
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime

/**
 * Shared conversion helpers for JDBC values returned by Groovy SQL.
 */
final class SqlValueMapper {

  private SqlValueMapper() {
  }

  static LocalDate toLocalDate(Object value) {
    if (value == null) {
      return null
    }
    if (value instanceof LocalDate) {
      return (LocalDate) value
    }
    if (value instanceof Date) {
      return ((Date) value).toLocalDate()
    }
    throw new IllegalStateException("Unsupported date value: ${value.class.name}")
  }

  static LocalDateTime toLocalDateTime(Object value) {
    if (value == null) {
      return null
    }
    if (value instanceof LocalDateTime) {
      return (LocalDateTime) value
    }
    if (value instanceof Timestamp) {
      return ((Timestamp) value).toLocalDateTime()
    }
    if (value instanceof OffsetDateTime) {
      // The app treats database timestamps as local wall-clock values.
      // When H2 returns OffsetDateTime for current_timestamp we intentionally
      // drop the offset instead of converting across zones.
      return ((OffsetDateTime) value).toLocalDateTime()
    }
    throw new IllegalStateException("Unsupported timestamp value: ${value.class.name}")
  }
}
