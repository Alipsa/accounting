package se.alipsa.accounting.domain

import groovy.transform.Canonical

/**
 * Chart of accounts entry imported from BAS or maintained in the app.
 */
@Canonical
final class Account {

  String accountNumber
  String accountName
  String accountClass
  String normalBalanceSide
  String vatCode
  boolean active = true
  boolean manualReviewRequired = false
  String classificationNote

  boolean isBalanceAccount() {
    accountClass in ['ASSET', 'LIABILITY', 'EQUITY']
  }
}
