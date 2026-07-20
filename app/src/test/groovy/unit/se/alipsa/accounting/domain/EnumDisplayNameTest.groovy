package unit.se.alipsa.accounting.domain

import static org.junit.jupiter.api.Assertions.*

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import se.alipsa.accounting.domain.AccountingMethod
import se.alipsa.accounting.domain.VatCode
import se.alipsa.accounting.domain.VatPeriodicity
import se.alipsa.accounting.domain.report.ReportType
import se.alipsa.accounting.support.I18n

import javax.swing.JComponent

final class EnumDisplayNameTest {

  private Locale previousComponentLocale

  @BeforeEach
  void setUp() {
    previousComponentLocale = JComponent.defaultLocale
    I18n.instance.setLocale(Locale.ENGLISH)
  }

  @AfterEach
  void tearDown() {
    JComponent.setDefaultLocale(previousComponentLocale)
  }

  @Test
  void vatCodeDisplayNamesResolveInEnglish() {
    assertEquals('Output VAT 25%', VatCode.OUTPUT_25.displayName)
    assertEquals('Reverse charge domestic', VatCode.REVERSE_CHARGE_DOMESTIC.displayName)
  }

  @Test
  void vatCodeDisplayNamesResolveInSwedish() {
    I18n.instance.setLocale(Locale.forLanguageTag('sv'))
    assertEquals('Utgående moms 25 %', VatCode.OUTPUT_25.displayName)
    assertEquals('Omvänd skattskyldighet inom Sverige', VatCode.REVERSE_CHARGE_DOMESTIC.displayName)
  }

  @Test
  void allVatCodeValuesHaveDisplayNames() {
    VatCode.values().each { VatCode code ->
      String name = code.displayName
      assertFalse(name.startsWith('['), "Missing key for ${code.name()}: ${name}")
    }
  }

  @Test
  void vatPeriodicityDisplayNamesResolve() {
    assertEquals('Monthly', VatPeriodicity.MONTHLY.displayName)
    I18n.instance.setLocale(Locale.forLanguageTag('sv'))
    assertEquals('Månadsvis', VatPeriodicity.MONTHLY.displayName)
  }

  @Test
  void allVatPeriodicityValuesHaveDisplayNames() {
    VatPeriodicity.values().each { VatPeriodicity periodicity ->
      String name = periodicity.displayName
      assertFalse(name.startsWith('['), "Missing key for ${periodicity.name()}: ${name}")
    }
  }

  @Test
  void accountingMethodDisplayNamesResolve() {
    assertEquals('Cash accounting', AccountingMethod.CASH.displayName)
    I18n.instance.setLocale(Locale.forLanguageTag('sv'))
    assertEquals('Fakturametoden', AccountingMethod.INVOICE.displayName)
  }

  @Test
  void allAccountingMethodValuesHaveDisplayNames() {
    AccountingMethod.values().each { AccountingMethod method ->
      String name = method.displayName
      assertFalse(name.startsWith('['), "Missing key for ${method.name()}: ${name}")
    }
  }

  @Test
  void reportTypeDisplayNamesResolve() {
    assertEquals('Voucher list', ReportType.VOUCHER_LIST.displayName)
    I18n.instance.setLocale(Locale.forLanguageTag('sv'))
    assertEquals('Verifikationslista', ReportType.VOUCHER_LIST.displayName)
  }

  @Test
  void allReportTypeValuesHaveDisplayNames() {
    ReportType.values().each { ReportType type ->
      String name = type.displayName
      assertFalse(name.startsWith('['), "Missing key for ${type.name()}: ${name}")
    }
  }

  @Test
  void reportTypeToStringUsesDisplayName() {
    assertEquals('Voucher list', ReportType.VOUCHER_LIST.toString())
  }
}
