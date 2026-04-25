package se.alipsa.accounting.service

/**
 * Raised when a posting or correction is attempted inside a closed fiscal year.
 */
final class ClosedFiscalYearException extends IllegalStateException {

  ClosedFiscalYearException(String message) {
    super(message)
  }
}
