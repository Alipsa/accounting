package unit.se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals

import alipsa.sieparser.SIE
import alipsa.sieparser.SieAccount
import alipsa.sieparser.SieVoucher
import alipsa.sieparser.SieVoucherRow
import org.junit.jupiter.api.Test

import se.alipsa.accounting.domain.VoucherLine
import se.alipsa.accounting.service.SieImportExportService

final class SieVoucherImportTest {

  private static List<VoucherLine> buildVoucherLines(SieVoucher voucher) {
    SieImportExportService.metaClass.invokeStaticMethod(
        SieImportExportService,
        'buildVoucherLines',
        [voucher] as Object[]
    ) as List<VoucherLine>
  }

  @Test
  void buildVoucherLinesIgnoresBtransAndRtransRows() {
    SieVoucher voucher = new SieVoucher()
    voucher.series = 'A'
    voucher.number = '1'
    voucher.voucherDate = java.time.LocalDate.of(2026, 1, 15)
    voucher.text = 'Testverifikat'

    voucher.rows = [
        transRow('1930', -1000.00),
        btransRow('1930', -500.00),
        rtransRow('1930', -200.00),
        transRow('3010', 1000.00)
    ]

    List<VoucherLine> lines = buildVoucherLines(voucher)

    assertEquals(2, lines.size())
    assertEquals('1930', lines[0].accountNumber)
    assertEquals(0.00G, lines[0].debitAmount)
    assertEquals(1000.00G, lines[0].creditAmount)
    assertEquals('3010', lines[1].accountNumber)
    assertEquals(1000.00G, lines[1].debitAmount)
    assertEquals(0.00G, lines[1].creditAmount)
  }

  @Test
  void buildVoucherLinesReturnsEmptyListForVoucherWithOnlyBtransRows() {
    SieVoucher voucher = new SieVoucher()
    voucher.series = 'A'
    voucher.number = '2'
    voucher.voucherDate = java.time.LocalDate.of(2026, 1, 15)
    voucher.text = 'Borttaget'

    voucher.rows = [
        btransRow('1930', -1000.00),
        btransRow('3010', 1000.00)
    ]

    assertEquals([], buildVoucherLines(voucher))
  }

  @Test
  void buildVoucherLinesReturnsEmptyListForVoucherWithOnlyRtransRows() {
    SieVoucher voucher = new SieVoucher()
    voucher.series = 'A'
    voucher.number = '3'
    voucher.voucherDate = java.time.LocalDate.of(2026, 1, 15)
    voucher.text = 'Rättat'

    voucher.rows = [
        rtransRow('1930', -1000.00),
        rtransRow('3010', 1000.00)
    ]

    assertEquals([], buildVoucherLines(voucher))
  }

  private static SieVoucherRow transRow(String accountNumber, BigDecimal amount) {
    SieVoucherRow row = new SieVoucherRow()
    row.account = account(accountNumber)
    row.amount = amount
    row.token = SIE.TRANS
    row
  }

  private static SieVoucherRow btransRow(String accountNumber, BigDecimal amount) {
    SieVoucherRow row = new SieVoucherRow()
    row.account = account(accountNumber)
    row.amount = amount
    row.token = SIE.BTRANS
    row
  }

  private static SieVoucherRow rtransRow(String accountNumber, BigDecimal amount) {
    SieVoucherRow row = new SieVoucherRow()
    row.account = account(accountNumber)
    row.amount = amount
    row.token = SIE.RTRANS
    row
  }

  private static SieAccount account(String number) {
    SieAccount acc = new SieAccount()
    acc.number = number
    acc
  }
}
