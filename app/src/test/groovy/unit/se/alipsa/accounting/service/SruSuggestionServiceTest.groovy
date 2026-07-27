package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.Test

import se.alipsa.accounting.domain.AccountingMethod
import se.alipsa.accounting.domain.Company
import se.alipsa.accounting.domain.LegalForm
import se.alipsa.accounting.domain.SruSignCondition
import se.alipsa.accounting.domain.SruSuggestion
import se.alipsa.accounting.domain.VatPeriodicity

class SruSuggestionServiceTest {

  private final SruSuggestionService service = new SruSuggestionService()

  @Test
  void reproducesAllRealExportFixturePairs() {
    Company aktiebolag = testCompany(LegalForm.AKTIEBOLAG, false)
    int checked = 0
    getClass().getResourceAsStream('/sru/ink2-real-export-fixture.csv').withReader('UTF-8') { reader ->
      reader.readLine() // header
      String line
      while ((line = reader.readLine()) != null) {
        if (!line.trim()) {
          continue
        }
        String[] parts = line.split(',')
        String accountNumber = parts[0]
        String expectedCode = parts[1]
        List<SruSuggestion> suggestions = service.suggest(aktiebolag, accountNumber)
        assertTrue(
            suggestions.any { it.fieldCode == expectedCode },
            "Expected ${accountNumber} to suggest ${expectedCode}, got ${suggestions*.fieldCode}"
        )
        checked++
      }
    }
    assertEquals(208, checked)
  }

  @Test
  void knownGapAccountsReturnNoSuggestion() {
    Company aktiebolag = testCompany(LegalForm.AKTIEBOLAG, false)
    assertEquals([], service.suggest(aktiebolag, '8710'))
    assertEquals([], service.suggest(aktiebolag, '8750'))
  }

  @Test
  void signDependentAccountReturnsBothCandidates() {
    Company aktiebolag = testCompany(LegalForm.AKTIEBOLAG, false)
    // 8810 appears twice in ink2.csv: field 7420 (Om netto +) and field 7525 (Om netto -)
    List<SruSuggestion> suggestions = service.suggest(aktiebolag, '8810')
    assertEquals(2, suggestions.size())
    assertTrue(suggestions.any { it.fieldCode == '7420' && it.signCondition == SruSignCondition.NET_POSITIVE })
    assertTrue(suggestions.any { it.fieldCode == '7525' && it.signCondition == SruSignCondition.NET_NEGATIVE })
  }

  @Test
  void unmappedAccountReturnsEmptyList() {
    Company aktiebolag = testCompany(LegalForm.AKTIEBOLAG, false)
    assertEquals([], service.suggest(aktiebolag, '0001'))
  }

  @Test
  void legalFormUnsetReturnsEmptyList() {
    Company noLegalForm = testCompany(null, false)
    assertEquals([], service.suggest(noLegalForm, '1630'))
  }

  @Test
  void nullCompanyReturnsEmptyList() {
    assertEquals([], service.suggest(null, '1630'))
  }

  @Test
  void neverSuggestsForSecondaryCodeSlot() {
    // sru_code2 has no suggestion source at all - suggest() only ever returns primary candidates
    Company aktiebolag = testCompany(LegalForm.AKTIEBOLAG, false)
    List<SruSuggestion> suggestions = service.suggest(aktiebolag, '6072')
    assertEquals(1, suggestions.size())
    assertEquals('7513', suggestions[0].fieldCode)
  }

  @Test
  void expectedRowCountsPerTable() {
    assertEquals(8519, service.tableSizeForTesting(LegalForm.AKTIEBOLAG, false))
    assertEquals(8603, service.tableSizeForTesting(LegalForm.HANDELSBOLAG_KB, false))
    assertEquals(105, service.tableSizeForTesting(LegalForm.ENSKILD_FIRMA, true))
    assertEquals(7440, service.tableSizeForTesting(LegalForm.ENSKILD_FIRMA, false))
  }

  private static Company testCompany(LegalForm legalForm, boolean simplifiedAnnualReport) {
    new Company(1L, 'Test AB', '556000-0000', 'SEK', 'sv-SE', VatPeriodicity.MONTHLY,
        true, null, null, false, AccountingMethod.CASH, legalForm, simplifiedAnnualReport)
  }
}
