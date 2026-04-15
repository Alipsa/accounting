package unit.se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals

import org.junit.jupiter.api.Test

import se.alipsa.accounting.service.AccountService

class AccountBalanceTest {

  @Test
  void applyDebitToDebitNormalAccount() {
    BigDecimal before = new BigDecimal('1000.00')
    BigDecimal debit = new BigDecimal('500.00')
    BigDecimal credit = BigDecimal.ZERO
    BigDecimal after = AccountService.calculateBalanceAfter(before, debit, credit, 'DEBIT')
    assertEquals(new BigDecimal('1500.00'), after)
  }

  @Test
  void applyCreditToDebitNormalAccount() {
    BigDecimal before = new BigDecimal('1000.00')
    BigDecimal debit = BigDecimal.ZERO
    BigDecimal credit = new BigDecimal('300.00')
    BigDecimal after = AccountService.calculateBalanceAfter(before, debit, credit, 'DEBIT')
    assertEquals(new BigDecimal('700.00'), after)
  }

  @Test
  void applyDebitToCreditNormalAccount() {
    BigDecimal before = new BigDecimal('5000.00')
    BigDecimal debit = new BigDecimal('200.00')
    BigDecimal credit = BigDecimal.ZERO
    BigDecimal after = AccountService.calculateBalanceAfter(before, debit, credit, 'CREDIT')
    assertEquals(new BigDecimal('4800.00'), after)
  }

  @Test
  void applyCreditToCreditNormalAccount() {
    BigDecimal before = new BigDecimal('5000.00')
    BigDecimal debit = BigDecimal.ZERO
    BigDecimal credit = new BigDecimal('1000.00')
    BigDecimal after = AccountService.calculateBalanceAfter(before, debit, credit, 'CREDIT')
    assertEquals(new BigDecimal('6000.00'), after)
  }

  @Test
  void zeroBeforeWithNoTransactions() {
    BigDecimal after = AccountService.calculateBalanceAfter(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 'DEBIT')
    assertEquals(BigDecimal.ZERO.setScale(2), after)
  }
}
