package se.alipsa.accounting.mcp

import static org.junit.jupiter.api.Assertions.*

import org.junit.jupiter.api.Test

class McpOperationCoordinatorTest {

  @Test
  void writeCallsUseTheUiGuardAndReadNotificationsDoNot() {
    CountingGuard guard = new CountingGuard()
    McpOperationCoordinator coordinator = new McpOperationCoordinator(guard)
    try {
      McpDispatcher dispatcher = new McpDispatcher()
      Object readResponse = coordinator.dispatch(dispatcher, [jsonrpc: '2.0', method: 'notifications/initialized'])
      assertNull(readResponse)
      assertEquals(0, guard.begins)
      coordinator.dispatch(dispatcher, [jsonrpc: '2.0', id: 1, method: 'tools/call', params: [name: 'set_active_voucher_draft']])
      assertEquals(1, guard.begins)
      assertEquals(1, guard.ends)
    } finally {
      coordinator.close()
    }
  }

  private static final class CountingGuard implements McpUiGuard {
    int begins
    int ends

    @Override void beginWrite() { begins++ }
    @Override void endWrite() { ends++ }
  }
}
