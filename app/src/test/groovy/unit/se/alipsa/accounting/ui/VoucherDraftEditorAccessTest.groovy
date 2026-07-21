package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.Test

import java.util.concurrent.atomic.AtomicBoolean

import javax.swing.SwingUtilities

final class VoucherDraftEditorAccessTest {

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
