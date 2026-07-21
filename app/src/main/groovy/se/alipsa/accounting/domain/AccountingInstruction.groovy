package se.alipsa.accounting.domain

import groovy.transform.Canonical

import java.time.LocalDateTime

/** A company-approved instruction for recurring accounting events. */
@Canonical
final class AccountingInstruction {

  Long id
  long companyId
  String triggerText
  String description
  String debitAccountNumber
  String creditAccountNumber
  String seriesCode
  LocalDateTime createdAt
  LocalDateTime updatedAt
}
