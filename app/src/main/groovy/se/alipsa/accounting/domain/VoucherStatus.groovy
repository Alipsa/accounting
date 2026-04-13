package se.alipsa.accounting.domain

/**
 * Lifecycle states for vouchers.
 */
enum VoucherStatus {
  DRAFT,
  BOOKED,
  CANCELLED,
  CORRECTION
}
