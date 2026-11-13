package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.Test

import se.alipsa.accounting.domain.VoucherLine
import se.alipsa.accounting.support.I18n

import java.awt.Container
import java.nio.file.Files
import java.time.LocalDate

import javax.swing.JButton
import javax.swing.JTable
import javax.swing.JTextField

final class VoucherDraftProtectionTest extends VoucherPanelTestSupport {

  @Test
  void blankEditorAcceptsDraftButAnotherDraftRequiresExplicitReplacement() {
    assertEquals([:], panel.mcpVoucherDraftAccess.getVoucherDraft())
    panel.mcpVoucherDraftAccess.setVoucherDraft(proposal())
    Map<String, Object> before = panel.mcpVoucherDraftAccess.getVoucherDraft()

    assertReplacementRefused()
    assertEquals(before, panel.mcpVoucherDraftAccess.getVoucherDraft())

    panel.mcpVoucherDraftAccess.setVoucherDraft(proposal([description: 'Replacement', replace_unsaved: true]))
    assertEquals('Replacement', panel.mcpVoucherDraftAccess.getVoucherDraft().description)
  }

  @Test
  void descriptionOnlyAndIncompleteLineContentAreProtected() {
    JTextField description = findComponent(panel, JTextField) { JTextField field -> field.columns == 30 }
    onEdt { description.text = 'Manual work' }
    assertReplacementRefused()
    assertEquals('Manual work', onEdt { description.text })

    onEdt {
      description.text = ''
      panel.lineTableModel.rows[0].description = 'Unfinished line'
    }
    assertReplacementRefused()
    assertEquals('Unfinished line', onEdt { panel.lineTableModel.rows[0].description })
  }

  @Test
  void activeCellEditorIsProtectedBeforeItsTextReachesTheModel() {
    JTable table = findComponent(panel, JTable) { JTable candidate -> candidate.model == panel.lineTableModel }
    onEdt {
      assertTrue(table.editCellAt(0, 4))
      ((JTextField) table.editorComponent).text = 'Still typing'
    }
    assertReplacementRefused()
    assertEquals('Still typing', onEdt { ((JTextField) table.editorComponent).text })
    panel.mcpVoucherDraftAccess.setVoucherDraft(proposal([replace_unsaved: true]))
    assertFalse(onEdt { table.editing })
    assertEquals([], panel.mcpVoucherDraftAccess.getVoucherDraft().lines)
  }

  @Test
  void attachmentOnlyDraftIsProtected() {
    def receipt = tempDir.resolve('receipt.txt')
    Files.writeString(receipt, 'Receipt')
    panel.mcpVoucherDraftAccess.setVoucherDraft(proposal([description: '', attachment_path: receipt.toString()]))
    assertReplacementRefused()
    assertEquals(receipt.toString(), panel.mcpVoucherDraftAccess.getVoucherDraft().attachment_path)
  }

  @Test
  void rememberedDraftIsProtectedWhileBrowsingReadOnlySavedVoucher() {
    voucherService.createVoucher(fiscalYear.id, 'A', LocalDate.of(2030, 3, 15), 'Saved voucher',
        [voucherLine('1510', 'Kundfordringar', 'Saved line', 100.00G, 0.00G),
         voucherLine('3010', 'Försäljning', 'Saved line', 0.00G, 100.00G)])
    panel.dispose()
    panel = buildPanel()
    panel.mcpVoucherDraftAccess.setVoucherDraft(proposal())
    onEdt { clickButtonWithTooltip(panel, I18n.instance.getString('voucherPanel.button.prev')) }

    assertEquals('AI proposal', panel.mcpVoucherDraftAccess.getVoucherDraft().description)
    assertReplacementRefused()
    onEdt { clickButtonWithTooltip(panel, I18n.instance.getString('voucherPanel.button.next')) }
    assertEquals('AI proposal', panel.mcpVoucherDraftAccess.getVoucherDraft().description)
  }

  private void assertReplacementRefused() {
    IllegalArgumentException exception = assertThrows(IllegalArgumentException) {
      panel.mcpVoucherDraftAccess.setVoucherDraft(proposal())
    }
    assertTrue(exception.message.contains('replace_unsaved: true'))
    assertThrows(IllegalArgumentException) {
      panel.mcpVoucherDraftAccess.setVoucherDraft(proposal([replace_unsaved: false]))
    }
  }

  private static Map<String, Object> proposal(Map<String, Object> overrides = [:]) {
    Map<String, Object> draft = [accounting_date: '2030-03-15', description: 'AI proposal', lines: []]
    draft.putAll(overrides)
    draft
  }

  private static VoucherLine voucherLine(String accountNumber, String accountName, String description,
      BigDecimal debit, BigDecimal credit) {
    new VoucherLine(null, null, 0, null, accountNumber, accountName, description, debit, credit)
  }

  private static void clickButtonWithTooltip(Container root, String tooltip) {
    JButton button = findComponent(root, JButton) { JButton candidate -> candidate.toolTipText == tooltip }
    button.doClick()
  }
}
