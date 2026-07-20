package se.alipsa.accounting.ui

/**
 * Chooses the next amount field and carries the opposite amount across a voucher pair.
 */
final class VoucherPairing {

  static Suggestion suggest(List<VoucherPanel.LineEntry> entries, int row, String normalBalanceSide) {
    VoucherPanel.LineEntry previous = row > 0 ? entries[row - 1] : null
    if (hasText(previous?.credit)) {
      return new Suggestion(2, previous.credit)
    }
    if (hasText(previous?.debit)) {
      return new Suggestion(3, previous.debit)
    }
    new Suggestion('CREDIT' == normalBalanceSide ? 3 : 2, null)
  }

  private static boolean hasText(String value) {
    value != null && !value.isBlank()
  }

  static final class Suggestion {

    final int column
    final String amount

    Suggestion(int column, String amount) {
      this.column = column
      this.amount = amount
    }
  }
}
