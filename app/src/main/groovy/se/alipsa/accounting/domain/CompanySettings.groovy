package se.alipsa.accounting.domain

import groovy.transform.Canonical
import groovy.transform.CompileStatic

/**
 * Company metadata stored once per database installation.
 */
@Canonical
@CompileStatic
final class CompanySettings {

  Long id
  String companyName
  String organizationNumber
  String defaultCurrency
  String localeTag
}
