package se.alipsa.accounting.domain

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class AccountSubgroupTest {

  @ParameterizedTest
  @CsvSource([
      '1010, INTANGIBLE_ASSETS',
      '1099, INTANGIBLE_ASSETS',
      '1110, BUILDINGS_AND_LAND',
      '1210, MACHINERY',
      '1310, FINANCIAL_FIXED_ASSETS',
      '1410, INVENTORY',
      '1510, RECEIVABLES',
      '1610, OTHER_CURRENT_RECEIVABLES',
      '1710, PREPAID_EXPENSES',
      '1810, SHORT_TERM_INVESTMENTS',
      '1910, CASH_AND_BANK',
      '2010, EQUITY',
      '2099, EQUITY',
      '2110, UNTAXED_RESERVES',
      '2210, PROVISIONS',
      '2310, LONG_TERM_LIABILITIES',
      '2410, SHORT_TERM_LIABILITIES_CREDIT',
      '2510, TAX_LIABILITIES',
      '2610, VAT_AND_EXCISE',
      '2710, PAYROLL_TAXES',
      '2810, OTHER_CURRENT_LIABILITIES',
      '2910, ACCRUED_EXPENSES',
      '3010, NET_REVENUE',
      '3499, NET_REVENUE',
      '3510, INVOICED_COSTS',
      '3610, SECONDARY_INCOME',
      '3710, REVENUE_ADJUSTMENTS',
      '3810, CAPITALIZED_WORK',
      '3910, OTHER_OPERATING_INCOME',
      '4010, RAW_MATERIALS',
      '4999, RAW_MATERIALS',
      '5010, OTHER_EXTERNAL_COSTS',
      '6910, OTHER_EXTERNAL_COSTS',
      '7010, PERSONNEL_COSTS',
      '7699, PERSONNEL_COSTS',
      '7710, DEPRECIATION',
      '7899, DEPRECIATION',
      '7910, OTHER_OPERATING_COSTS',
      '8010, FINANCIAL_INCOME',
      '8399, FINANCIAL_INCOME',
      '8410, FINANCIAL_COSTS',
      '8810, APPROPRIATIONS',
      '8910, TAX_AND_RESULT'
  ])
  void fromAccountNumberMapsAllBasGroups(String accountNumber, String expectedName) {
    assertEquals(AccountSubgroup.valueOf(expectedName), AccountSubgroup.fromAccountNumber(accountNumber))
  }

  @Test
  void fromAccountNumberReturnsNullForUnmappedPrefix() {
    assertNull(AccountSubgroup.fromAccountNumber('0010'))
  }

  @Test
  void fromDatabaseValueReturnsEnumForValidName() {
    assertEquals(AccountSubgroup.NET_REVENUE, AccountSubgroup.fromDatabaseValue('NET_REVENUE'))
  }

  @Test
  void fromDatabaseValueReturnsNullForBlank() {
    assertNull(AccountSubgroup.fromDatabaseValue(null))
    assertNull(AccountSubgroup.fromDatabaseValue(''))
    assertNull(AccountSubgroup.fromDatabaseValue('  '))
  }
}
