package se.alipsa.accounting.domain

/**
 * Supported VAT classifications used on accounts for reporting and transfer logic.
 */
enum VatCode {

  OUTPUT_25('Utgående moms 25 %', 0.25G, 0.00G),
  OUTPUT_12('Utgående moms 12 %', 0.12G, 0.00G),
  OUTPUT_6('Utgående moms 6 %', 0.06G, 0.00G),
  INPUT_25('Ingående moms 25 %', 0.00G, 0.25G),
  INPUT_12('Ingående moms 12 %', 0.00G, 0.12G),
  INPUT_6('Ingående moms 6 %', 0.00G, 0.06G),
  REVERSE_CHARGE_DOMESTIC('Omvänd skattskyldighet inom Sverige', 0.25G, 0.25G),
  EU_ACQUISITION_GOODS('EU-förvärv varor', 0.25G, 0.25G),
  EU_ACQUISITION_SERVICES('EU-förvärv tjänster', 0.25G, 0.25G),
  EU_SUPPLY_GOODS('EU-försäljning varor', 0.00G, 0.00G),
  EU_SUPPLY_SERVICES('EU-försäljning tjänster', 0.00G, 0.00G),
  EXEMPT('Undantagen omsättning', 0.00G, 0.00G),
  OUTSIDE_SCOPE('Utanför momsens tillämpningsområde', 0.00G, 0.00G)

  final String label
  final BigDecimal outputRate
  final BigDecimal inputRate

  VatCode(String label, BigDecimal outputRate, BigDecimal inputRate) {
    this.label = label
    this.outputRate = outputRate
    this.inputRate = inputRate
  }

  static VatCode fromDatabaseValue(String value) {
    String normalized = value?.trim()
    normalized ? valueOf(normalized) : null
  }
}
