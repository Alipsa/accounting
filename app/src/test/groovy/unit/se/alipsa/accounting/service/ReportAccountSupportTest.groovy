package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.Test

import se.alipsa.accounting.domain.AccountSubgroup

class ReportAccountSupportTest {

  @Test
  void shouldExclude8999FromIncomeStatement() {
    assertTrue(ReportAccountSupport.shouldExcludeFromIncomeStatement('8999', AccountSubgroup.TAX_AND_RESULT))
  }

  @Test
  void shouldNotExclude8910FromIncomeStatement() {
    assertFalse(ReportAccountSupport.shouldExcludeFromIncomeStatement('8910', AccountSubgroup.TAX_AND_RESULT))
  }

  @Test
  void shouldNotExclude8999WithOtherSubgroup() {
    assertFalse(ReportAccountSupport.shouldExcludeFromIncomeStatement('8999', AccountSubgroup.FINANCIAL_COSTS))
  }

  @Test
  void inferIncomeAccountClassReturnsIncomeForRevenueSubgroups() {
    assertEquals('INCOME', ReportAccountSupport.inferIncomeAccountClass(AccountSubgroup.NET_REVENUE))
    assertEquals('INCOME', ReportAccountSupport.inferIncomeAccountClass(AccountSubgroup.FINANCIAL_INCOME))
    assertEquals('INCOME', ReportAccountSupport.inferIncomeAccountClass(AccountSubgroup.OTHER_OPERATING_INCOME))
  }

  @Test
  void inferIncomeAccountClassReturnsExpenseForCostSubgroups() {
    assertEquals('EXPENSE', ReportAccountSupport.inferIncomeAccountClass(AccountSubgroup.RAW_MATERIALS))
    assertEquals('EXPENSE', ReportAccountSupport.inferIncomeAccountClass(AccountSubgroup.PERSONNEL_COSTS))
    assertEquals('EXPENSE', ReportAccountSupport.inferIncomeAccountClass(AccountSubgroup.TAX_AND_RESULT))
  }

  @Test
  void inferIncomeAccountClassReturnsNullForBalanceSubgroups() {
    assertNull(ReportAccountSupport.inferIncomeAccountClass(AccountSubgroup.CASH_AND_BANK))
    assertNull(ReportAccountSupport.inferIncomeAccountClass(AccountSubgroup.EQUITY))
  }

  @Test
  void inferNormalBalanceSideForAllAccountClasses() {
    assertEquals('DEBIT', ReportAccountSupport.inferNormalBalanceSide('ASSET'))
    assertEquals('DEBIT', ReportAccountSupport.inferNormalBalanceSide('EXPENSE'))
    assertEquals('CREDIT', ReportAccountSupport.inferNormalBalanceSide('LIABILITY'))
    assertEquals('CREDIT', ReportAccountSupport.inferNormalBalanceSide('EQUITY'))
    assertEquals('CREDIT', ReportAccountSupport.inferNormalBalanceSide('INCOME'))
    assertNull(ReportAccountSupport.inferNormalBalanceSide('UNKNOWN'))
    assertNull(ReportAccountSupport.inferNormalBalanceSide(null))
  }

  @Test
  void resolveUsesStoredNormalBalanceSideFirst() {
    assertEquals('DEBIT', ReportAccountSupport.resolveSignedMovementNormalSide(
        '1920', 'DEBIT', 'ASSET', null))
    assertEquals('CREDIT', ReportAccountSupport.resolveSignedMovementNormalSide(
        '1920', ' credit ', 'ASSET', null))
  }

  @Test
  void resolveFallsBackToAccountClass() {
    assertEquals('DEBIT', ReportAccountSupport.resolveSignedMovementNormalSide(
        '1920', null, 'ASSET', null))
    assertEquals('CREDIT', ReportAccountSupport.resolveSignedMovementNormalSide(
        '2010', null, 'EQUITY', null))
  }

  @Test
  void resolveFallsBackToSubgroupBasRange() {
    assertEquals('DEBIT', ReportAccountSupport.resolveSignedMovementNormalSide(
        '1920', null, null, 'CASH_AND_BANK'))
    assertEquals('CREDIT', ReportAccountSupport.resolveSignedMovementNormalSide(
        '2010', null, null, 'EQUITY'))
  }

  @Test
  void resolveFallsBackToAccountNumber() {
    assertEquals('DEBIT', ReportAccountSupport.resolveSignedMovementNormalSide(
        '1920', null, null, null))
    assertEquals('CREDIT', ReportAccountSupport.resolveSignedMovementNormalSide(
        '2010', null, null, null))
  }

  @Test
  void resolveFallsBackToIncomeClassFromSubgroup() {
    assertEquals('CREDIT', ReportAccountSupport.resolveSignedMovementNormalSide(
        '3010', null, null, 'NET_REVENUE'))
    assertEquals('DEBIT', ReportAccountSupport.resolveSignedMovementNormalSide(
        '4010', null, null, 'RAW_MATERIALS'))
  }

  @Test
  void resolveThrowsWhenNothingResolves() {
    assertThrows(IllegalStateException) {
      ReportAccountSupport.resolveSignedMovementNormalSide('XXXX', null, null, null)
    }
  }

  @Test
  void resolveThrowsWithDistinctMessageWhenSubgroupIsNull() {
    IllegalStateException ex = assertThrows(IllegalStateException) {
      ReportAccountSupport.resolveSignedMovementNormalSide('XXXX', null, null, null)
    }
    assertTrue(ex.message.contains('saknar undergrupp'))
  }

  @Test
  void inferIncomeAccountClassFromAccountNumber() {
    assertEquals('INCOME', ReportAccountSupport.inferIncomeAccountClassFromAccountNumber('3010'))
    assertEquals('EXPENSE', ReportAccountSupport.inferIncomeAccountClassFromAccountNumber('4010'))
    assertNull(ReportAccountSupport.inferIncomeAccountClassFromAccountNumber('1920'))
  }
}
