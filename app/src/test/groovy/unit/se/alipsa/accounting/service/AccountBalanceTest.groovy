package unit.se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals

import org.junit.jupiter.api.Test

import se.alipsa.accounting.service.AccountService

class AccountBalanceTest {

  @Test
  void applyDebitToDebitNormalAccount() {
    BigDecimal before = 1000.00G
    BigDecimal debit = 500.00G
    BigDecimal credit = BigDecimal.ZERO
    BigDecimal after = AccountService.calculateBalanceAfter(before, debit, credit, 'DEBIT')
    assertEquals(1500.00G, after)
  }

  @Test
  void applyCreditToDebitNormalAccount() {
    BigDecimal before = 1000.00G
    BigDecimal debit = BigDecimal.ZERO
    BigDecimal credit = 300.00G
    BigDecimal after = AccountService.calculateBalanceAfter(before, debit, credit, 'DEBIT')
    assertEquals(700.00G, after)
  }

  @Test
  void applyDebitToCreditNormalAccount() {
    BigDecimal before = 5000.00G
    BigDecimal debit = 200.00G
    BigDecimal credit = BigDecimal.ZERO
    BigDecimal after = AccountService.calculateBalanceAfter(before, debit, credit, 'CREDIT')
    assertEquals(4800.00G, after)
  }

  @Test
  void applyCreditToCreditNormalAccount() {
    BigDecimal before = 5000.00G
    BigDecimal debit = BigDecimal.ZERO
    BigDecimal credit = 1000.00G
    BigDecimal after = AccountService.calculateBalanceAfter(before, debit, credit, 'CREDIT')
    assertEquals(6000.00G, after)
  }

  @Test
  void zeroBeforeWithNoTransactions() {
    BigDecimal after = AccountService.calculateBalanceAfter(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 'DEBIT')
    assertEquals(BigDecimal.ZERO.setScale(2), after)
  }
}
