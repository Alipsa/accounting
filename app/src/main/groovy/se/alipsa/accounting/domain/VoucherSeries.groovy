package se.alipsa.accounting.domain

import groovy.transform.Canonical

/**
 * Number series for vouchers within one fiscal year.
 */
@Canonical
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
