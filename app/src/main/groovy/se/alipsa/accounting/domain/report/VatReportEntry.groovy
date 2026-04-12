package se.alipsa.accounting.domain.report

import groovy.transform.Canonical
import groovy.transform.CompileStatic

/**
 * One summarized VAT code row in the report preview/export layer.
 */
@Canonical
@CompileStatic
final class VatReportEntry {

  String vatCode
  String label
  BigDecimal baseAmount
  BigDecimal outputVatAmount
  BigDecimal inputVatAmount
}
