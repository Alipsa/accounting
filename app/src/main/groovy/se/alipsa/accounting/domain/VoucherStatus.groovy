package se.alipsa.accounting.domain

import groovy.transform.CompileStatic

/**
 * Lifecycle states for vouchers.
 */
@CompileStatic
enum VoucherStatus {
  DRAFT,
  BOOKED,
  CANCELLED,
  CORRECTION
}
