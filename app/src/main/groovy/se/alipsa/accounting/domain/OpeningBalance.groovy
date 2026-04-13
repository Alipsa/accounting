package se.alipsa.accounting.domain

import groovy.transform.Canonical

/**
 * Opening balance stored per fiscal year and account.
 */
@Canonical
final class OpeningBalance {

  Long fiscalYearId
  Long accountId
  String accountNumber
  BigDecimal amount = BigDecimal.ZERO
}
