package se.alipsa.accounting.service

import groovy.transform.Canonical
import groovy.transform.CompileStatic

import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.Voucher

@Canonical
@CompileStatic
final class YearEndClosingPreview {

  FiscalYear fiscalYear
  FiscalYear nextFiscalYear
  boolean nextFiscalYearWillBeCreated
  String closingAccountNumber
  int resultAccountCount
  BigDecimal incomeTotal
  BigDecimal expenseTotal
  BigDecimal netResult
  List<String> blockingIssues = []
  List<String> warnings = []
}

@Canonical
@CompileStatic
final class YearEndClosingResult {

  FiscalYear closedFiscalYear
  FiscalYear nextFiscalYear
  Voucher closingVoucher
  int resultAccountCount
  int openingBalanceCount
  int closingEntryCount
  BigDecimal netResult
  List<String> warnings = []
}

@Canonical
@CompileStatic
final class NextFiscalYearPlan {

  FiscalYear fiscalYear
  boolean willCreate
  String conflictMessage
}

@Canonical
@CompileStatic
final class ResultAccountBalance {

  String accountNumber
  String accountName
  String accountClass
  String normalBalanceSide
  BigDecimal amount
}

@Canonical
@CompileStatic
final class BalanceAccountSeed {

  String accountNumber
  String accountName
  String accountClass
  String normalBalanceSide
  boolean active
}

@Canonical
@CompileStatic
final class ClosingExecution {

  Voucher closingVoucher
  int openingBalanceCount
  int closingEntryCount
}
