package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

import alipsa.sieparser.SieCompany
import org.junit.jupiter.api.Test

import se.alipsa.accounting.domain.Company

class SieExchangeCompanyMatchTest {

  @Test
  void matchesWhenSieCompanyIsNull() {
    assertTrue(SieExchangeDialog.companiesMatch(makeCompany('AB', '556677-8899'), null))
  }

  @Test
  void matchesOnIdenticalOrgNumber() {
    assertTrue(SieExchangeDialog.companiesMatch(
        makeCompany('AB', '556677-8899'),
        makeSie('AB', '556677-8899')))
  }

  @Test
  void matchesOrgNumberIgnoringDashesAndSpaces() {
    assertTrue(SieExchangeDialog.companiesMatch(
        makeCompany('AB', '556677-8899'),
        makeSie('AB', '5566778899')))
  }

  @Test
  void matchesOrgNumberCaseInsensitive() {
    assertTrue(SieExchangeDialog.companiesMatch(
        makeCompany('AB', 'SE556677-8899'),
        makeSie('AB', 'se556677-8899')))
  }

  @Test
  void doesNotMatchOnDifferentOrgNumber() {
    assertFalse(SieExchangeDialog.companiesMatch(
        makeCompany('AB', '556677-8899'),
        makeSie('AB', '112233-4455')))
  }

  @Test
  void fallsBackToNameWhenOrgNumberIsMissing() {
    assertTrue(SieExchangeDialog.companiesMatch(
        makeCompany('Testbolaget AB', null),
        makeSie('Testbolaget AB', null)))
  }

  @Test
  void nameMatchIsCaseInsensitive() {
    assertTrue(SieExchangeDialog.companiesMatch(
        makeCompany('Testbolaget AB', null),
        makeSie('testbolaget ab', null)))
  }

  @Test
  void doesNotMatchOnDifferentName() {
    assertFalse(SieExchangeDialog.companiesMatch(
        makeCompany('Företag A', null),
        makeSie('Företag B', null)))
  }

  @Test
  void defaultsToMatchWhenNeitherOrgNorNameAvailable() {
    assertTrue(SieExchangeDialog.companiesMatch(
        makeCompany(null, null),
        makeSie(null, null)))
  }

  @Test
  void normalizeOrgNumberStripsSpacesAndDashes() {
    assert SieExchangeDialog.normalizeOrgNumber('556677-8899') == '5566778899'
    assert SieExchangeDialog.normalizeOrgNumber('55 66 77-88 99') == '5566778899'
    assert SieExchangeDialog.normalizeOrgNumber(null) == ''
  }

  private static Company makeCompany(String name, String orgNumber) {
    new Company(
        id: 1L,
        companyName: name,
        organizationNumber: orgNumber
    )
  }

  private static SieCompany makeSie(String name, String orgIdentifier) {
    SieCompany sie = new SieCompany()
    sie.name = name
    sie.orgIdentifier = orgIdentifier
    sie
  }
}
