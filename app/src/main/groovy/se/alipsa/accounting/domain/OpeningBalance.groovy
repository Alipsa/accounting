package se.alipsa.accounting.domain

import groovy.transform.Canonical
import groovy.transform.CompileStatic

/**
 * Opening balance stored per fiscal year and account.
 */
@Canonical
@CompileStatic
final class OpeningBalance {

  Long fiscalYearId
  String accountNumber
  BigDecimal amount = BigDecimal.ZERO
}
