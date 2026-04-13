package se.alipsa.accounting.domain

import groovy.transform.Canonical

/**
 * Company metadata stored once per database installation.
 */
@Canonical
final class CompanySettings {

  Long id
  String companyName
  String organizationNumber
  String defaultCurrency
  String localeTag
  VatPeriodicity vatPeriodicity = VatPeriodicity.MONTHLY
}
