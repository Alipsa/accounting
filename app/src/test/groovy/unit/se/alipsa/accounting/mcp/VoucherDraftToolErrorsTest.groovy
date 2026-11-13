package se.alipsa.accounting.mcp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertSame
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.Test

import se.alipsa.accounting.ui.VoucherDraftEditorAccess
import se.alipsa.accounting.ui.VoucherDraftMapper

import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger

final class VoucherDraftToolErrorsTest {

  private final AccountingMcpTools tools = new AccountingMcpTools(null, null, null, null, null, null, null, null, null)

  @Test
  void unavailableEditorReportsUnknownUnsavedState() {
    Map<String, Object> result = tools.callTool('get_active_voucher_draft', [:])
    assertEquals(false, result.ok)
    assertEquals('editor_unavailable', result.error_code)
    assertTrue(result.containsKey('has_unsaved_changes'))
    assertNull(result.has_unsaved_changes)
  }

  @Test
  void readFailureIsAToolResultWithoutRawExceptionText() {
    NullPointerException failure = new NullPointerException('Internal detail')
    tools.setVoucherDraftAccess(new VoucherDraftEditorAccess({ throw failure }, { true },
        { VoucherDraftMapper.VoucherDraft ignored -> }))
    List<LogRecord> records = []
    Handler handler = new RecordingHandler(records)
    Logger logger = Logger.getLogger(AccountingMcpTools.name)
    logger.addHandler(handler)
    try {
      Map<String, Object> result = tools.callTool('get_active_voucher_draft', [:])
      assertEquals(false, result.ok)
      assertEquals('draft_unavailable', result.error_code)
      assertFalse(result.errors.toString().contains('Internal detail'))
      assertSame(failure, records.last().thrown)
    } finally {
      logger.removeHandler(handler)
    }
  }

  @Test
  void absentDraftHasAStableErrorCode() {
    Map<String, Object> emptyDraft = [:]
    tools.setVoucherDraftAccess(new VoucherDraftEditorAccess({ emptyDraft }, { false },
        { VoucherDraftMapper.VoucherDraft ignored -> }))
    Map<String, Object> result = tools.callTool('get_active_voucher_draft', [:])
    assertEquals(false, result.ok)
    assertEquals(false, result.has_unsaved_changes)
    assertEquals('no_draft', result.error_code)
  }

  @Test
  void stateConflictsHaveStructuredFieldsWhileBadArgumentsStillThrow() {
    Map<String, Object> draft = [description: 'Manual work', lines: [], editing: true]
    tools.setVoucherDraftAccess(new VoucherDraftEditorAccess({ draft }, { true },
        { VoucherDraftMapper.VoucherDraft ignored -> }))
    Map<String, Object> result = tools.callTool('set_active_voucher_draft',
        [accounting_date: '2030-03-15', description: 'AI proposal', lines: []])
    assertEquals(false, result.ok)
    assertEquals('unsaved_changes', result.error_code)
    assertEquals(true, result.has_unsaved_changes)
    Map<String, Object> readResult = tools.callTool('get_active_voucher_draft', [:])
    assertEquals(true, readResult.editing)
    assertFalse(((Map) readResult.draft).containsKey('editing'))
    assertEquals(true, draft.editing)

    assertThrows(IllegalArgumentException) {
      tools.callTool('set_active_voucher_draft', [accounting_date: 'invalid', lines: []])
    }
  }

  private static final class RecordingHandler extends Handler implements AutoCloseable {
    private final List<LogRecord> records

    RecordingHandler(List<LogRecord> records) {
      this.records = records
    }

    @Override void publish(LogRecord record) { records.add(record) }
    @Override void flush() { }
    @Override void close() { }
  }
}
