package se.alipsa.accounting.domain

import groovy.transform.CompileStatic

/**
 * Lifecycle states for recorded import attempts.
 */
@CompileStatic
enum ImportJobStatus {
  STARTED,
  SUCCESS,
  FAILED,
  DUPLICATE
}
