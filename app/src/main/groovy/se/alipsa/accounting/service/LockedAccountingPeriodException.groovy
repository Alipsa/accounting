package se.alipsa.accounting.service

import groovy.transform.CompileStatic

/**
 * Raised when a posting is attempted inside a locked accounting period.
 */
@CompileStatic
final class LockedAccountingPeriodException extends IllegalStateException {

  LockedAccountingPeriodException(String message) {
    super(message)
  }
}
