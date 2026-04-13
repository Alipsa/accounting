package se.alipsa.accounting.domain.report

import groovy.transform.Canonical

/**
 * One summarized VAT code row in the report preview/export layer.
 */
@Canonical
final class VatReportEntry {

  String vatCode
  String label
  BigDecimal baseAmount
  BigDecimal outputVatAmount
  BigDecimal inputVatAmount
}
