package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.*

import groovy.sql.Sql

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.Account
import se.alipsa.accounting.domain.VatCode
import se.alipsa.accounting.support.AppPaths

import java.nio.file.Path

class AccountServiceTest {

  @TempDir
  Path tempDir

  private DatabaseService databaseService
  private AccountService accountService
  private String previousHome

  @BeforeEach
  void setUp() {
    previousHome = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, tempDir.toString())
    databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    accountService = new AccountService(databaseService)
    insertTestAccounts()
  }

  @AfterEach
  void tearDown() {
    if (previousHome == null) {
      System.clearProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    } else {
      System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, previousHome)
    }
  }

  @Test
  void setAccountVatCodeAcceptsCompatibleCode() {
    accountService.setAccountVatCode(
        CompanyService.LEGACY_COMPANY_ID, '1510', VatCode.INPUT_25)

    Account updated = accountService.findAccount(
        CompanyService.LEGACY_COMPANY_ID, '1510')
    assertEquals(VatCode.INPUT_25.name(), updated.vatCode)
  }

  @Test
  void setAccountVatCodeAcceptsCompatibleLiabilityCode() {
    accountService.setAccountVatCode(
        CompanyService.LEGACY_COMPANY_ID, '2611', VatCode.OUTPUT_25)

    Account updated = accountService.findAccount(
        CompanyService.LEGACY_COMPANY_ID, '2611')
    assertEquals(VatCode.OUTPUT_25.name(), updated.vatCode)
  }

  @Test
  void setAccountVatCodeRejectsIncompatibleCode() {
    IllegalArgumentException exception = assertThrows(IllegalArgumentException) {
      accountService.setAccountVatCode(
          CompanyService.LEGACY_COMPANY_ID, '1510', VatCode.OUTPUT_25)
    }

    assertTrue(exception.message.contains('inte kompatibelt'))

    Account unchanged = accountService.findAccount(
        CompanyService.LEGACY_COMPANY_ID, '1510')
    assertNull(unchanged.vatCode)
  }

  @Test
  void setAccountVatCodeRejectsUnknownAccount() {
    IllegalArgumentException exception = assertThrows(IllegalArgumentException) {
      accountService.setAccountVatCode(
          CompanyService.LEGACY_COMPANY_ID, '9999', VatCode.INPUT_25)
    }

    assertTrue(exception.message.contains(AccountService.UNKNOWN_ACCOUNT_MESSAGE_PREFIX))
  }

  @Test
  void setAccountVatCodeRejectsInputCodeOnIncomeAccount() {
    IllegalArgumentException exception = assertThrows(IllegalArgumentException) {
      accountService.setAccountVatCode(
          CompanyService.LEGACY_COMPANY_ID, '3010', VatCode.EU_ACQUISITION_GOODS)
    }

    assertTrue(exception.message.contains('inte kompatibelt'))
  }

  @Test
  void setAccountVatCodeRejectsOutputCodeOnExpenseAccount() {
    IllegalArgumentException exception = assertThrows(IllegalArgumentException) {
      accountService.setAccountVatCode(
          CompanyService.LEGACY_COMPANY_ID, '4010', VatCode.OUTPUT_25)
    }

    assertTrue(exception.message.contains('inte kompatibelt'))
  }

  @Test
  void setAccountVatCodeRejectsEuReverseChargeCodeOnExpenseAccount() {
    IllegalArgumentException exception = assertThrows(IllegalArgumentException) {
      accountService.setAccountVatCode(
          CompanyService.LEGACY_COMPANY_ID, '4010', VatCode.REVERSE_CHARGE_EU_25)
    }

    assertTrue(exception.message.contains('inte kompatibelt'))
  }

  @Test
  void setAccountVatCodeRejectsEuReverseChargeCodeOnAssetAccount() {
    IllegalArgumentException exception = assertThrows(IllegalArgumentException) {
      accountService.setAccountVatCode(
          CompanyService.LEGACY_COMPANY_ID, '1510', VatCode.REVERSE_CHARGE_EU_25)
    }

    assertTrue(exception.message.contains('inte kompatibelt'))
  }

  @Test
  void setAccountVatCodeToNullClearsExistingCode() {
    accountService.setAccountVatCode(
        CompanyService.LEGACY_COMPANY_ID, '1510', VatCode.INPUT_25)
    accountService.setAccountVatCode(
        CompanyService.LEGACY_COMPANY_ID, '1510', null)

    Account cleared = accountService.findAccount(
        CompanyService.LEGACY_COMPANY_ID, '1510')
    assertNull(cleared.vatCode)
  }

  @Test
  void compatibleVatCodesFiltersByAccountClass() {
    Account asset = accountService.findAccount(
        CompanyService.LEGACY_COMPANY_ID, '1510')
    List<VatCode> assetCodes = AccountService.compatibleVatCodes(asset)
    assertTrue(assetCodes.contains(VatCode.INPUT_25))
    assertTrue(assetCodes.contains(VatCode.EU_ACQUISITION_GOODS))
    assertTrue(assetCodes.contains(VatCode.EU_ACQUISITION_SERVICES))
    assertTrue(assetCodes.contains(VatCode.REVERSE_CHARGE_DOMESTIC))
    assertFalse(assetCodes.contains(VatCode.OUTPUT_25))
    assertFalse(assetCodes.contains(VatCode.REVERSE_CHARGE_EU_25))

    Account liability = accountService.findAccount(
        CompanyService.LEGACY_COMPANY_ID, '2611')
    List<VatCode> liabilityCodes = AccountService.compatibleVatCodes(liability)
    assertTrue(liabilityCodes.contains(VatCode.OUTPUT_25))
    assertTrue(liabilityCodes.contains(VatCode.REVERSE_CHARGE_EU_25))
    assertTrue(liabilityCodes.contains(VatCode.EXEMPT))
    assertFalse(liabilityCodes.contains(VatCode.INPUT_25))
    assertFalse(liabilityCodes.contains(VatCode.EU_ACQUISITION_GOODS))
    assertFalse(liabilityCodes.contains(VatCode.EU_ACQUISITION_SERVICES))

    Account income = accountService.findAccount(
        CompanyService.LEGACY_COMPANY_ID, '3010')
    List<VatCode> incomeCodes = AccountService.compatibleVatCodes(income)
    assertTrue(incomeCodes.contains(VatCode.OUTPUT_25))
    assertTrue(incomeCodes.contains(VatCode.EU_SUPPLY_GOODS))
    assertFalse(incomeCodes.contains(VatCode.INPUT_25))
    assertFalse(incomeCodes.contains(VatCode.REVERSE_CHARGE_DOMESTIC))
    assertFalse(incomeCodes.contains(VatCode.REVERSE_CHARGE_EU_25))

    Account expense = accountService.findAccount(
        CompanyService.LEGACY_COMPANY_ID, '4010')
    List<VatCode> expenseCodes = AccountService.compatibleVatCodes(expense)
    assertTrue(expenseCodes.contains(VatCode.INPUT_25))
    assertTrue(expenseCodes.contains(VatCode.EU_ACQUISITION_GOODS))
    assertTrue(expenseCodes.contains(VatCode.REVERSE_CHARGE_DOMESTIC))
    assertFalse(expenseCodes.contains(VatCode.OUTPUT_25))
    assertFalse(expenseCodes.contains(VatCode.REVERSE_CHARGE_EU_25))
    assertFalse(expenseCodes.contains(VatCode.EU_SUPPLY_GOODS))

    Account equity = accountService.findAccount(
        CompanyService.LEGACY_COMPANY_ID, '2010')
    List<VatCode> equityCodes = AccountService.compatibleVatCodes(equity)
    assertTrue(equityCodes.isEmpty())
  }

  private void insertTestAccounts() {
    databaseService.withTransaction { Sql sql ->
      sql.executeInsert('''
          insert into account (
              company_id, account_number, account_name,
              account_class, normal_balance_side, active,
              manual_review_required, created_at, updated_at
          ) values (1, '1510', 'Kundfordringar', 'ASSET', 'DEBIT',
              true, false, current_timestamp, current_timestamp)
      ''')
      sql.executeInsert('''
          insert into account (
              company_id, account_number, account_name,
              account_class, normal_balance_side, active,
              manual_review_required, created_at, updated_at
          ) values (1, '2611', 'Utgående moms', 'LIABILITY', 'CREDIT',
              true, false, current_timestamp, current_timestamp)
      ''')
      sql.executeInsert('''
          insert into account (
              company_id, account_number, account_name,
              account_class, normal_balance_side, active,
              manual_review_required, created_at, updated_at
          ) values (1, '2010', 'Eget kapital', 'EQUITY', 'CREDIT',
              true, false, current_timestamp, current_timestamp)
      ''')
      sql.executeInsert('''
          insert into account (
              company_id, account_number, account_name,
              account_class, normal_balance_side, active,
              manual_review_required, created_at, updated_at
          ) values (1, '3010', 'Försäljning', 'INCOME', 'CREDIT',
              true, false, current_timestamp, current_timestamp)
      ''')
      sql.executeInsert('''
          insert into account (
              company_id, account_number, account_name,
              account_class, normal_balance_side, active,
              manual_review_required, created_at, updated_at
          ) values (1, '4010', 'Varuinköp', 'EXPENSE', 'DEBIT',
              true, false, current_timestamp, current_timestamp)
      ''')
    }
  }
}
