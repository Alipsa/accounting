package se.alipsa.accounting.domain

import groovy.transform.Canonical
import groovy.transform.CompileStatic

/**
 * Number series for vouchers within one fiscal year.
 */
@Canonical
@CompileStatic
final class VoucherSeries {

  Long id
  long fiscalYearId
  String seriesCode
  String seriesName
  int nextRunningNumber

  @Override
  String toString() {
    "${seriesCode} - ${seriesName}"
  }
}
