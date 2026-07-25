package se.alipsa.accounting.domain

import java.util.logging.Level
import java.util.logging.Logger

/**
 * Sort order used when listing vouchers in the voucher editor.
 */
enum VoucherSortOrder {

  BY_VOUCHER_NUMBER,
  BY_ACCOUNTING_DATE

  private static final Logger log = Logger.getLogger(VoucherSortOrder.name)

  static VoucherSortOrder fromName(String name) {
    if (name == null) {
      return BY_VOUCHER_NUMBER
    }
    try {
      return valueOf(name)
    } catch (IllegalArgumentException ignored) {
      log.log(Level.WARNING, "Unknown voucher sort order preference ''{0}'', falling back to BY_VOUCHER_NUMBER.", name)
      return BY_VOUCHER_NUMBER
    }
  }
}
