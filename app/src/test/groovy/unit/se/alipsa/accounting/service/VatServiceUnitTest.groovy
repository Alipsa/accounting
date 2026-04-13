package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.Test

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

class VatServiceUnitTest {

  @Test
  void signedAmountUsesNormalBalanceSide() {
    assertEquals(75.00G, invokeSignedAmount(100.00G, 25.00G, 'DEBIT'))
    assertEquals(75.00G, invokeSignedAmount(25.00G, 100.00G, 'CREDIT'))
  }

  @Test
  void signedAmountRejectsMissingBalanceSide() {
    InvocationTargetException exception = assertThrows(InvocationTargetException) {
      invokeSignedAmount(100.00G, 25.00G, null)
    }

    assertTrue(exception.cause instanceof IllegalStateException)
    assertTrue(exception.cause.message.contains('normal balanssida'))
  }

  private static BigDecimal invokeSignedAmount(BigDecimal debitAmount, BigDecimal creditAmount, String normalBalanceSide) {
    Method method = VatService.getDeclaredMethod('signedAmount', BigDecimal, BigDecimal, String)
    method.accessible = true
    method.invoke(null, debitAmount, creditAmount, normalBalanceSide) as BigDecimal
  }
}
