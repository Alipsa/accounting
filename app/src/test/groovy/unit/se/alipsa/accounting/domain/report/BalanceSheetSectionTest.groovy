package se.alipsa.accounting.domain.report

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.Test

import se.alipsa.accounting.domain.AccountSubgroup

class BalanceSheetSectionTest {

  @Test
  void fixedAssetsContainsExpectedSubgroups() {
    assertEquals(
        [AccountSubgroup.INTANGIBLE_ASSETS, AccountSubgroup.BUILDINGS_AND_LAND,
         AccountSubgroup.MACHINERY, AccountSubgroup.FINANCIAL_FIXED_ASSETS],
        BalanceSheetSection.FIXED_ASSETS.subgroups
    )
    assertFalse(BalanceSheetSection.FIXED_ASSETS.computed)
  }

  @Test
  void currentAssetsContainsExpectedSubgroups() {
    assertEquals(
        [AccountSubgroup.INVENTORY, AccountSubgroup.RECEIVABLES,
         AccountSubgroup.OTHER_CURRENT_RECEIVABLES, AccountSubgroup.PREPAID_EXPENSES,
         AccountSubgroup.SHORT_TERM_INVESTMENTS, AccountSubgroup.CASH_AND_BANK],
        BalanceSheetSection.CURRENT_ASSETS.subgroups
    )
    assertFalse(BalanceSheetSection.CURRENT_ASSETS.computed)
  }

  @Test
  void totalAssetsIsComputed() {
    assertTrue(BalanceSheetSection.TOTAL_ASSETS.computed)
    assertTrue(BalanceSheetSection.TOTAL_ASSETS.subgroups.isEmpty())
  }

  @Test
  void currentLiabilitiesContainsExpectedSubgroups() {
    assertEquals(
        [AccountSubgroup.SHORT_TERM_LIABILITIES_CREDIT, AccountSubgroup.TAX_LIABILITIES,
         AccountSubgroup.VAT_AND_EXCISE, AccountSubgroup.PAYROLL_TAXES,
         AccountSubgroup.OTHER_CURRENT_LIABILITIES, AccountSubgroup.ACCRUED_EXPENSES],
        BalanceSheetSection.CURRENT_LIABILITIES.subgroups
    )
  }

  @Test
  void totalEquityAndLiabilitiesIsComputed() {
    assertTrue(BalanceSheetSection.TOTAL_EQUITY_AND_LIABILITIES.computed)
  }

  @Test
  void everyBalanceAccountSubgroupAppearsInExactlyOneSection() {
    List<AccountSubgroup> balanceSubgroups = AccountSubgroup.values().findAll { AccountSubgroup sg ->
      sg.basGroupStart >= 10 && sg.basGroupEnd <= 29
    }
    List<AccountSubgroup> allMapped = BalanceSheetSection.values()
        .collectMany { BalanceSheetSection section -> section.subgroups }
    balanceSubgroups.each { AccountSubgroup sg ->
      assertEquals(1, allMapped.count { AccountSubgroup mapped -> mapped == sg },
          "AccountSubgroup ${sg.name()} should appear in exactly one section")
    }
  }
}
