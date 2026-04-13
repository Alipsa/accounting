package se.alipsa.accounting.domain

/**
 * Lifecycle states for recorded import attempts.
 */
enum ImportJobStatus {
  STARTED,
  SUCCESS,
  FAILED,
  DUPLICATE
}
