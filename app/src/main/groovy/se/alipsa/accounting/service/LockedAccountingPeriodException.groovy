package se.alipsa.accounting.service

/**
 * Raised when a posting is attempted inside a locked accounting period.
 */
final class LockedAccountingPeriodException extends IllegalStateException {

  LockedAccountingPeriodException(String message) {
    super(message)
  }
}
