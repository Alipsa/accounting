package se.alipsa.accounting.domain

import groovy.transform.Canonical

/**
 * One debit or credit line in a voucher.
 */
@Canonical
@SuppressWarnings('ParameterCount')
final class VoucherLine {

  Long id
  Long voucherId
  int lineIndex
  Long accountId
  String accountNumber
  String accountName
  String description
  BigDecimal debitAmount = BigDecimal.ZERO
  BigDecimal creditAmount = BigDecimal.ZERO

  VoucherLine(
      Long id,
      Long voucherId,
      int lineIndex,
      Long accountId,
      String accountNumber,
      String accountName,
      String description,
      BigDecimal debitAmount = BigDecimal.ZERO,
      BigDecimal creditAmount = BigDecimal.ZERO
  ) {
    this.id = id
    this.voucherId = voucherId
    this.lineIndex = lineIndex
    this.accountId = accountId
    this.accountNumber = accountNumber
    this.accountName = accountName
    this.description = description
    this.debitAmount = debitAmount
    this.creditAmount = creditAmount
  }

  @Deprecated
  VoucherLine(
      Long id,
      Long voucherId,
      int lineIndex,
      String accountNumber,
      String accountName,
      String description,
      BigDecimal debitAmount = BigDecimal.ZERO,
      BigDecimal creditAmount = BigDecimal.ZERO
  ) {
    this(id, voucherId, lineIndex, (Long) null, accountNumber, accountName, description, debitAmount, creditAmount)
  }
}
