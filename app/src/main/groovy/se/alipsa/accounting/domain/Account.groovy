package se.alipsa.accounting.domain

import groovy.transform.Canonical
import groovy.transform.CompileStatic

/**
 * Chart of accounts entry imported from BAS or maintained in the app.
 */
@Canonical
@CompileStatic
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
