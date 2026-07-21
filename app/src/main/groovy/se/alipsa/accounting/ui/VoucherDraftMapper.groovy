package se.alipsa.accounting.ui

import se.alipsa.accounting.domain.VoucherLine

import java.time.LocalDate

/** Converts voucher-editor state to and from the MCP voucher-draft representation. */
final class VoucherDraftMapper {

  static Map<String, Object> toDraft(LocalDate accountingDate, String description, String seriesCode, List<VoucherLine> lines) {
    [
        accounting_date: accountingDate?.toString(),
        description: description ?: '',
        series_code: seriesCode?.trim() ?: 'A',
        lines: lines.collect { VoucherLine line ->
          [account_number: line.accountNumber, account_name: line.accountName, description: line.description,
           debit: line.debitAmount, credit: line.creditAmount]
        }
    ]
  }

  static VoucherDraft fromDraft(Map<String, Object> draft) {
    Object linesValue = draft.get('lines')
    if (!(linesValue instanceof List)) {
      throw new IllegalArgumentException('lines must be an array.')
    }
    List<VoucherLine> lines = []
    ((List) linesValue).eachWithIndex { Object value, int index ->
      if (!(value instanceof Map)) {
        throw new IllegalArgumentException('Each voucher line must be an object.')
      }
      Map line = (Map) value
      lines << new VoucherLine(null, null, index, null, line.account_number as String, line.account_name as String,
          line.description as String, decimal(line.debit), decimal(line.credit))
    }
    LocalDate accountingDate
    try {
      accountingDate = LocalDate.parse(draft.get('accounting_date') as String)
    } catch (Exception exception) {
      throw new IllegalArgumentException('accounting_date must be an ISO date (YYYY-MM-DD).', exception)
    }
    new VoucherDraft(accountingDate, draft.get('description') as String ?: '', draft.get('series_code') as String ?: 'A', lines)
  }

  private static BigDecimal decimal(Object value) {
    value == null || value.toString().trim().isEmpty() ? BigDecimal.ZERO : new BigDecimal(value.toString())
  }

  static final class VoucherDraft {
    final LocalDate accountingDate
    final String description
    final String seriesCode
    final List<VoucherLine> lines

    VoucherDraft(LocalDate accountingDate, String description, String seriesCode, List<VoucherLine> lines) {
      this.accountingDate = accountingDate
      this.description = description
      this.seriesCode = seriesCode
      this.lines = lines
    }
  }
}
