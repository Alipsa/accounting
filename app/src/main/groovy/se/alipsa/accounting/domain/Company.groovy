package se.alipsa.accounting.domain

import groovy.transform.Canonical

import java.time.LocalDateTime

/**
 * Top-level company record for multi-company installations.
 */
@Canonical
final class Company {

  Long id
  String companyName
  String organizationNumber
  String defaultCurrency
  String localeTag
  VatPeriodicity vatPeriodicity = VatPeriodicity.MONTHLY
  boolean active = true
  LocalDateTime createdAt
  LocalDateTime updatedAt
}
