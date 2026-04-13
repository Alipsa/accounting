package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.Test

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

class VoucherServiceUnitTest {

  @Test
  void normalizeAccountNumberTrimsValidValue() {
    assertEquals('1510', invokeStaticStringMethod(VoucherService, 'normalizeAccountNumber', ' 1510 '))
  }

  @Test
  void normalizeAccountNumberRejectsInvalidValue() {
    InvocationTargetException exception = assertThrows(InvocationTargetException) {
      invokeStaticStringMethod(VoucherService, 'normalizeAccountNumber', '15A0')
    }

    assertTrue(exception.cause instanceof IllegalArgumentException)
    assertTrue(exception.cause.message.contains('fyra siffror'))
  }

  private static String invokeStaticStringMethod(Class<?> type, String methodName, String argument) {
    Method method = type.getDeclaredMethod(methodName, String)
    method.accessible = true
    method.invoke(null, argument) as String
  }
}
