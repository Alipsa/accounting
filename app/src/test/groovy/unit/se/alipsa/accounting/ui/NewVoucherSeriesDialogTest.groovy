package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull

import org.junit.jupiter.api.Test

final class NewVoucherSeriesDialogTest {

  @Test
  void normalizesValidCodesToUppercase() {
    assertEquals('B', NewVoucherSeriesDialog.normalizeCode('b'))
    assertEquals('B2', NewVoucherSeriesDialog.normalizeCode(' b2 '))
    assertEquals('ABCDEFGH', NewVoucherSeriesDialog.normalizeCode('abcdefgh'))
  }

  @Test
  void rejectsBlankCode() {
    assertNull(NewVoucherSeriesDialog.normalizeCode(''))
    assertNull(NewVoucherSeriesDialog.normalizeCode(null))
    assertNull(NewVoucherSeriesDialog.normalizeCode('   '))
  }

  @Test
  void rejectsCodesLongerThanEightCharacters() {
    assertNull(NewVoucherSeriesDialog.normalizeCode('ABCDEFGHI'))
  }

  @Test
  void rejectsCodesWithCharactersOutsideAToZAndZeroToNine() {
    assertNull(NewVoucherSeriesDialog.normalizeCode('B-1'))
    assertNull(NewVoucherSeriesDialog.normalizeCode('B 1'))
    assertNull(NewVoucherSeriesDialog.normalizeCode('Ö'))
  }
}
