package se.alipsa.accounting.domain

import groovy.transform.Canonical

/**
 * One debit or credit line in a voucher.
 */
@Canonical
final class VoucherLine {

  Long id
  Long voucherId
  int lineIndex
  String accountNumber
  String accountName
  String description
  BigDecimal debitAmount = BigDecimal.ZERO
  BigDecimal creditAmount = BigDecimal.ZERO
}
