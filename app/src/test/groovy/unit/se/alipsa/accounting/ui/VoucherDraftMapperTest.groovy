package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows

import org.junit.jupiter.api.Test

final class VoucherDraftMapperTest {

  @Test
  void convertsValidDraftToVoucherLines() {
    VoucherDraftMapper.VoucherDraft draft = VoucherDraftMapper.fromDraft([
        accounting_date: '2026-07-21',
        description: 'AI draft',
        series_code: 'B',
        lines: [[account_number: '1930', account_name: 'Bank', description: 'Payment', debit: '125.50', credit: null]]
    ])

    assertEquals('2026-07-21', draft.accountingDate.toString())
    assertEquals('AI draft', draft.description)
    assertEquals('B', draft.seriesCode)
    assertEquals(1, draft.lines.size())
    assertEquals(125.50G, draft.lines[0].debitAmount)
    assertEquals(BigDecimal.ZERO, draft.lines[0].creditAmount)
  }

  @Test
  void rejectsMalformedDraftValuesWithActionableErrors() {
    IllegalArgumentException missingLines = assertThrows(IllegalArgumentException) {
      VoucherDraftMapper.fromDraft([accounting_date: '2026-07-21'])
    }
    IllegalArgumentException invalidDate = assertThrows(IllegalArgumentException) {
      VoucherDraftMapper.fromDraft([accounting_date: 'not-a-date', lines: []])
    }

    assertEquals('lines must be an array.', missingLines.message)
    assertEquals('accounting_date must be an ISO date (YYYY-MM-DD).', invalidDate.message)
  }
}
