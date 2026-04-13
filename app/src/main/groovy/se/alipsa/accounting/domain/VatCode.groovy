package se.alipsa.accounting.domain

import se.alipsa.accounting.support.I18n

/**
 * Supported VAT classifications used on accounts for reporting and transfer logic.
 */
enum VatCode {

  OUTPUT_25(0.25G, 0.00G),
  OUTPUT_12(0.12G, 0.00G),
  OUTPUT_6(0.06G, 0.00G),
  INPUT_25(0.00G, 0.25G),
  INPUT_12(0.00G, 0.12G),
  INPUT_6(0.00G, 0.06G),
  REVERSE_CHARGE_DOMESTIC(0.25G, 0.25G),
  EU_ACQUISITION_GOODS(0.25G, 0.25G),
  EU_ACQUISITION_SERVICES(0.25G, 0.25G),
  EU_SUPPLY_GOODS(0.00G, 0.00G),
  EU_SUPPLY_SERVICES(0.00G, 0.00G),
  EXEMPT(0.00G, 0.00G),
  OUTSIDE_SCOPE(0.00G, 0.00G)

  final BigDecimal outputRate
  final BigDecimal inputRate

  VatCode(BigDecimal outputRate, BigDecimal inputRate) {
    this.outputRate = outputRate
    this.inputRate = inputRate
  }

  String getDisplayName() {
    I18n.instance.getString("vatCode.${name()}")
  }

  static VatCode fromDatabaseValue(String value) {
    String normalized = value?.trim()
    normalized ? valueOf(normalized) : null
  }
}
