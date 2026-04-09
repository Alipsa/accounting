package se.alipsa.accounting.service

import groovy.transform.CompileStatic

import java.sql.Date
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Shared conversion helpers for JDBC values returned by Groovy SQL.
 */
@CompileStatic
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
        throw new IllegalStateException("Unsupported timestamp value: ${value.class.name}")
    }
}
