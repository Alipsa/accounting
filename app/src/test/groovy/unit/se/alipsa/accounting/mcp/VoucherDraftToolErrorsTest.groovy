package se.alipsa.accounting.mcp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.Test

import se.alipsa.accounting.ui.VoucherDraftEditorAccess
import se.alipsa.accounting.ui.VoucherDraftMapper

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
    tools.setVoucherDraftAccess(new VoucherDraftAccess() {
      @Override Map<String, Object> getVoucherDraft() { throw new IllegalArgumentException('Internal detail') }
      @Override void setVoucherDraft(Map<String, Object> ignored) { }
    })
    Map<String, Object> result = tools.callTool('get_active_voucher_draft', [:])
    assertEquals(false, result.ok)
    assertEquals('draft_unavailable', result.error_code)
    assertFalse(result.errors.toString().contains('Internal detail'))
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
    assertEquals(true, tools.callTool('get_active_voucher_draft', [:]).editing)

    assertThrows(IllegalArgumentException) {
      tools.callTool('set_active_voucher_draft', [accounting_date: 'invalid', lines: []])
    }
  }
}
