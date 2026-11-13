package se.alipsa.accounting.service

import groovy.transform.Canonical

import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.Voucher

@Canonical
final class YearEndClosingPreview {

  FiscalYear fiscalYear
  FiscalYear nextFiscalYear
  boolean nextFiscalYearWillBeCreated
  String closingAccountNumber
  int resultAccountsWithBalanceCount
  BigDecimal incomeTotal
  BigDecimal expenseTotal
  BigDecimal netResult
  BigDecimal transferredResult
  BigDecimal remainingResult
  List<String> blockingIssues = []
  List<String> warnings = []
}

@Canonical
final class YearEndClosingResult {

  FiscalYear closedFiscalYear
  FiscalYear nextFiscalYear
  Voucher closingVoucher
  int resultAccountsWithBalanceCount
  int openingBalanceCount
  int closingEntryCount
  BigDecimal netResult
  BigDecimal transferredResult
  BigDecimal remainingResult
  List<String> warnings = []
}

@Canonical
final class NextFiscalYearPlan {

  FiscalYear fiscalYear
  boolean willCreate
  String conflictMessage
}

@Canonical
final class ResultAccountBalance {

  String accountNumber
  String accountName
  String accountClass
  String normalBalanceSide
  BigDecimal amount
}

@Canonical
final class BalanceAccountSeed {

  String accountNumber
  String accountName
  String accountClass
  String normalBalanceSide
  boolean active
}

@Canonical
final class ClosingExecution {

  Voucher closingVoucher
  int openingBalanceCount
  int closingEntryCount
}

@Canonical
final class ClosingResultTotals {

  BigDecimal incomeTotal
  BigDecimal expenseTotal
  BigDecimal netResult
  BigDecimal transferredResult
  BigDecimal remainingResult
}
