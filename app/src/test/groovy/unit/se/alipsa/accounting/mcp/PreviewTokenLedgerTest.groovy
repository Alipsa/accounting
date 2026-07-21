package se.alipsa.accounting.mcp

import static org.junit.jupiter.api.Assertions.*

import org.junit.jupiter.api.Test

class PreviewTokenLedgerTest {

  @Test
  void releasesAReservationWhenAnyGuardedWriteFails() {
    PreviewTokenLedger ledger = new PreviewTokenLedger()

    assertTrue(ledger.reserve('token'))
    assertThrows(IllegalStateException) {
      ledger.executeReservedWrite('token') { throw new IllegalStateException('write failed') }
    }
    assertTrue(ledger.reserve('token'))
  }

  @Test
  void keepsACompletedWriteConsumed() {
    PreviewTokenLedger ledger = new PreviewTokenLedger()

    assertTrue(ledger.reserve('token'))
    assertEquals('written', ledger.executeReservedWrite('token') { 'written' })
    // Response mapping happens after executeReservedWrite returns, so it cannot re-open this token.
    assertFalse(ledger.reserve('token'))
  }
}
