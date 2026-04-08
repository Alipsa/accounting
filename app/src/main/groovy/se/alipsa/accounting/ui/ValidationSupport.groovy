package se.alipsa.accounting.ui

import groovy.transform.CompileStatic

/**
 * Shared helpers for presenting validation feedback consistently in the UI.
 */
@CompileStatic
final class ValidationSupport {

    private ValidationSupport() {
    }

    static List<ValidationMessage> noIssues() {
        []
    }

    static ValidationMessage fieldError(String fieldName, String message, String suggestion = '') {
        new ValidationMessage(fieldName, message, suggestion)
    }

    static String summaryText(List<ValidationMessage> messages) {
        messages.collect { ValidationMessage message -> "• ${message.toSummaryLine()}" }.join(System.lineSeparator())
    }
}
