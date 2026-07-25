package unit.se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import se.alipsa.accounting.domain.VoucherSortOrder
import se.alipsa.accounting.service.UserPreferencesService

import java.util.prefs.Preferences

class UserPreferencesVoucherSortOrderTest {

  private Preferences node
  private UserPreferencesService service

  @BeforeEach
  void setUp() {
    node = Preferences.userRoot().node("accounting-test-${UUID.randomUUID()}")
    service = new UserPreferencesService(node)
  }

  @AfterEach
  void cleanup() {
    node.removeNode()
  }

  @Test
  void defaultVoucherSortOrderIsByVoucherNumber() {
    assertEquals(VoucherSortOrder.BY_VOUCHER_NUMBER, service.getVoucherSortOrder())
  }

  @Test
  void roundTripsVoucherSortOrder() {
    service.setVoucherSortOrder(VoucherSortOrder.BY_ACCOUNTING_DATE)
    assertEquals(VoucherSortOrder.BY_ACCOUNTING_DATE, service.getVoucherSortOrder())

    service.setVoucherSortOrder(VoucherSortOrder.BY_VOUCHER_NUMBER)
    assertEquals(VoucherSortOrder.BY_VOUCHER_NUMBER, service.getVoucherSortOrder())
  }
}
