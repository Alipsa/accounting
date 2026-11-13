package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertSame
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.Test

import se.alipsa.accounting.domain.VoucherLine
import se.alipsa.accounting.domain.VoucherSeries
import se.alipsa.accounting.mcp.UnsavedVoucherDraftException
import se.alipsa.accounting.support.I18n
import se.alipsa.datepicker.DatePicker

import java.awt.Container
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.event.TableModelListener

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
    JTextField description = descriptionEditor()
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
  void readingAnActiveCellLeavesTextModelAndListenersUntouched() {
    JTable table = findComponent(panel, JTable) { JTable candidate -> candidate.model == panel.lineTableModel }
    for (int column : [0, 2]) {
      onEdt {
        assertTrue(table.editCellAt(0, column))
        ((JTextField) table.editorComponent).text = column == 0 ? '19' : '12,'
      }
      def editor = onEdt { table.editorComponent }
      AtomicInteger events = new AtomicInteger()
      TableModelListener listener = { events.incrementAndGet() } as TableModelListener
      onEdt { panel.lineTableModel.addTableModelListener(listener) }
      try {
        int rowCount = onEdt { table.rowCount }
        Map<String, Object> draft = panel.mcpVoucherDraftAccess.getVoucherDraft()
        assertEquals(true, draft.editing)
        assertEquals([], draft.lines)
        assertTrue(onEdt { table.editing })
        assertSame(editor, onEdt { table.editorComponent })
        assertEquals(column == 0 ? '19' : '12,', onEdt { ((JTextField) table.editorComponent).text })
        assertEquals(rowCount, onEdt { table.rowCount })
        assertEquals(0, events.get())
      } finally {
        onEdt {
          panel.lineTableModel.removeTableModelListener(listener)
          table.cellEditor.cancelCellEditing()
        }
      }
    }
  }

  @Test
  void emptyActiveCellDoesNotCountAsUnsavedContentOrGetCommittedByReading() {
    JTable table = findComponent(panel, JTable) { JTable candidate -> candidate.model == panel.lineTableModel }
    onEdt { assertTrue(table.editCellAt(0, 4)) }
    assertEquals([:], panel.mcpVoucherDraftAccess.getVoucherDraft())
    assertTrue(onEdt { table.editing })
    panel.mcpVoucherDraftAccess.setVoucherDraft(proposal())
    assertFalse(onEdt { table.editing })
    assertEquals('AI proposal', panel.mcpVoucherDraftAccess.getVoucherDraft().description)
  }

  @Test
  void deliberateDateAndSeriesChangesAreProtectedButInitialDefaultsAreBlank() {
    voucherService.ensureSeries(fiscalYear.id, 'B')
    panel.dispose()
    panel = buildPanel()
    DatePicker picker = findComponent(panel, DatePicker) { true }
    JComboBox series = findComponent(panel, JComboBox) { JComboBox candidate ->
      candidate.itemCount > 0 && candidate.getItemAt(0) instanceof VoucherSeries
    }
    String changedSeries = onEdt { (series.selectedItem as VoucherSeries).seriesCode == 'A' ? 'B' : 'A' }
    LocalDate initialDate = onEdt { picker.date }
    assertEquals([:], panel.mcpVoucherDraftAccess.getVoucherDraft())
    onEdt { picker.date = initialDate.plusDays(1) }
    assertReplacementRefused()
    assertEquals(initialDate.plusDays(1).toString(), panel.mcpVoucherDraftAccess.getVoucherDraft().accounting_date)
    onEdt {
      picker.date = initialDate
      selectSeries(changedSeries)
    }
    assertReplacementRefused()
    assertEquals(changedSeries, panel.mcpVoucherDraftAccess.getVoucherDraft().series_code)
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

  @Test
  void blankDraftStaysBlankAcrossBrowsingAnotherSeries() {
    prepareCrossSeriesVoucher()
    assertEquals([:], panel.mcpVoucherDraftAccess.getVoucherDraft())
    browse('prev')
    assertEquals([:], panel.mcpVoucherDraftAccess.getVoucherDraft())
    browse('next')
    assertEquals([:], panel.mcpVoucherDraftAccess.getVoucherDraft())
    panel.mcpVoucherDraftAccess.setVoucherDraft(proposal())
    assertEquals('AI proposal', panel.mcpVoucherDraftAccess.getVoucherDraft().description)
  }

  @Test
  void deliberateSeriesChoiceRemainsProtectedWhileBrowsingAndAfterRepeatedReturns() {
    String savedSeries = prepareCrossSeriesVoucher()
    onEdt { selectSeries(savedSeries) }
    2.times {
      assertReplacementRefused()
      browse('prev')
      assertReplacementRefused()
      assertEquals(savedSeries, panel.mcpVoucherDraftAccess.getVoucherDraft().series_code)
      browse('next')
      assertReplacementRefused()
      assertEquals(savedSeries, panel.mcpVoucherDraftAccess.getVoucherDraft().series_code)
    }
  }

  @Test
  void deliberateDateRemainsProtectedWhileBrowsingAndAfterReturning() {
    prepareCrossSeriesVoucher()
    DatePicker picker = findComponent(panel, DatePicker) { true }
    LocalDate changedDate = onEdt { picker.date.plusDays(1) }
    onEdt { picker.date = changedDate }
    browse('prev')
    assertReplacementRefused()
    assertEquals(changedDate.toString(), panel.mcpVoucherDraftAccess.getVoucherDraft().accounting_date)
    browse('next')
    assertReplacementRefused()
    assertEquals(changedDate.toString(), panel.mcpVoucherDraftAccess.getVoucherDraft().accounting_date)
  }

  private String prepareCrossSeriesVoucher() {
    voucherService.ensureSeries(fiscalYear.id, 'B')
    onEdt { panel.applyVoucherSortOrderPreference() }
    JComboBox series = seriesCombo()
    String otherSeries = onEdt { (series.selectedItem as VoucherSeries).seriesCode == 'A' ? 'B' : 'A' }
    voucherService.createVoucher(fiscalYear.id, otherSeries, LocalDate.of(2030, 3, 15), 'Other series',
        [voucherLine('1510', 'Kundfordringar', 'Saved line', 100.00G, 0.00G),
         voucherLine('3010', 'Försäljning', 'Saved line', 0.00G, 100.00G)])
    onEdt { panel.applyVoucherSortOrderPreference() }
    otherSeries
  }

  private void browse(String direction) {
    onEdt { clickButtonWithTooltip(panel, I18n.instance.getString('voucherPanel.button.' + direction)) }
  }

  private JComboBox seriesCombo() {
    findComponent(panel, JComboBox) { JComboBox candidate ->
      candidate.itemCount > 0 && candidate.getItemAt(0) instanceof VoucherSeries
    }
  }

  private void selectSeries(String code) {
    JComboBox series = seriesCombo()
    series.selectedItem = (0..<series.itemCount).collect { int index -> series.getItemAt(index) }
        .find { Object item -> (item as VoucherSeries).seriesCode == code }
  }

  private JTextField descriptionEditor() {
    JLabel label = findComponent(panel, JLabel) { JLabel candidate ->
      candidate.text == I18n.instance.getString('voucherPanel.label.description')
    }
    label.labelFor as JTextField
  }

  private void assertReplacementRefused() {
    UnsavedVoucherDraftException exception = assertThrows(UnsavedVoucherDraftException) {
      panel.mcpVoucherDraftAccess.setVoucherDraft(proposal())
    }
    assertTrue(exception.message.contains('replace_unsaved: true'))
    assertThrows(UnsavedVoucherDraftException) {
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
