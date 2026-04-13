package se.alipsa.accounting.ui

import groovy.transform.Canonical

/**
 * Represents a single validation message in the shared UI validation pattern.
 */
@Canonical
final class ValidationMessage {

  String fieldName
  String message
  String suggestion = ''

  String toSummaryLine() {
    String prefix = fieldName ? "${fieldName}: " : ''
    String suffix = suggestion ? " (${suggestion})" : ''
    "${prefix}${message}${suffix}"
  }
}
