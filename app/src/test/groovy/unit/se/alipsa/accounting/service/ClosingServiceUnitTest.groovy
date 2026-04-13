package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.Test

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

class ClosingServiceUnitTest {

  @Test
  void normalizeAccountNumberAcceptsTrimmedFourDigitValue() {
    assertEquals('2099', invokeStaticStringMethod(ClosingService, 'normalizeAccountNumber', ' 2099 '))
  }

  @Test
  void normalizeAccountNumberRejectsMalformedValue() {
    InvocationTargetException exception = assertThrows(InvocationTargetException) {
      invokeStaticStringMethod(ClosingService, 'normalizeAccountNumber', '20-99')
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
