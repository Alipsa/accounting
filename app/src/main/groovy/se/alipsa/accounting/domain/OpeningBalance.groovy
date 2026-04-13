package se.alipsa.accounting.domain

import groovy.transform.Canonical

/**
 * Opening balance stored per fiscal year and account.
 */
@Canonical
final class OpeningBalance {

  Long fiscalYearId
  String accountNumber
  BigDecimal amount = BigDecimal.ZERO
}
