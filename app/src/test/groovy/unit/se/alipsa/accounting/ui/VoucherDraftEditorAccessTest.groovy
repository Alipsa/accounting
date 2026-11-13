package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.Test

import java.util.concurrent.atomic.AtomicBoolean

import javax.swing.SwingUtilities

final class VoucherDraftEditorAccessTest {

  @Test
  void checksForUnsavedContentAndAppliesReplacementTogetherOnTheEventDispatchThread() {
    AtomicBoolean dirty = new AtomicBoolean(true)
    AtomicBoolean applied = new AtomicBoolean(false)
    Map<String, Object> existingDraft = [description: 'Manual work']
    VoucherDraftEditorAccess access = new VoucherDraftEditorAccess(
        { existingDraft },
        { assertTrue(SwingUtilities.isEventDispatchThread()); dirty.get() },
        { VoucherDraftMapper.VoucherDraft ignored ->
          assertTrue(SwingUtilities.isEventDispatchThread())
          applied.set(true)
        }
    )
    Map<String, Object> draft = [accounting_date: '2030-03-15', description: 'AI proposal', lines: []]

    assertThrows(IllegalArgumentException) { access.setVoucherDraft(draft) }
    assertFalse(applied.get())
    draft.replace_unsaved = 'true'
    assertThrows(IllegalArgumentException) { access.setVoucherDraft(draft) }
    draft.replace_unsaved = true
    access.setVoucherDraft(draft)
    assertTrue(applied.get())

    draft.remove('replace_unsaved')
    dirty.set(false)
    applied.set(false)
    assertEquals([:], access.getVoucherDraft())
    access.setVoucherDraft(draft)
    assertTrue(applied.get())
  }

  @Test
  void readsDraftStateAndSnapshotTogetherOnTheEventDispatchThread() {
    AtomicBoolean stateReadOnEdt = new AtomicBoolean(false)
    AtomicBoolean snapshotReadOnEdt = new AtomicBoolean(false)
    Map<String, Object> draft = [description: 'Draft']
    VoucherDraftEditorAccess access = new VoucherDraftEditorAccess(
        { snapshotReadOnEdt.set(SwingUtilities.isEventDispatchThread()); draft },
        { stateReadOnEdt.set(SwingUtilities.isEventDispatchThread()); true },
        { VoucherDraftMapper.VoucherDraft ignored -> }
    )

    assertEquals([description: 'Draft'], access.getVoucherDraft())
    assertTrue(stateReadOnEdt.get())
    assertTrue(snapshotReadOnEdt.get())
  }
}
