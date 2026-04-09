package se.alipsa.accounting.domain

import groovy.transform.Canonical
import groovy.transform.CompileStatic

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Represents one accounting year with a fixed date range.
 */
@Canonical
@CompileStatic
final class FiscalYear {

    Long id
    String name
    LocalDate startDate
    LocalDate endDate
    boolean closed
    LocalDateTime closedAt
}
