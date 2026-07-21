package se.alipsa.accounting.ui

import se.alipsa.accounting.mcp.VoucherDraftAccess

import java.util.function.Consumer
import java.util.function.Supplier

/** MCP-facing adapter for reading and replacing the voucher editor's unsaved draft. */
final class VoucherDraftEditorAccess implements VoucherDraftAccess {

  private final Supplier<Map<String, Object>> draftSupplier
  private final Supplier<Boolean> unsavedDraftSupplier
  private final Consumer<VoucherDraftMapper.VoucherDraft> draftConsumer

  VoucherDraftEditorAccess(
      Supplier<Map<String, Object>> draftSupplier,
      Supplier<Boolean> unsavedDraftSupplier,
      Consumer<VoucherDraftMapper.VoucherDraft> draftConsumer
  ) {
    this.draftSupplier = draftSupplier
    this.unsavedDraftSupplier = unsavedDraftSupplier
    this.draftConsumer = draftConsumer
  }

  @Override
  Map<String, Object> getVoucherDraft() {
    if (!unsavedDraftSupplier.get()) {
      return [:]
    }
    Map<String, Object>[] holder = new Map[1]
    SwingThreading.runOnEdt { holder[0] = draftSupplier.get() }
    holder[0]
  }

  @Override
  void setVoucherDraft(Map<String, Object> draft) {
    VoucherDraftMapper.VoucherDraft voucherDraft = VoucherDraftMapper.fromDraft(draft)
    SwingThreading.runOnEdt { draftConsumer.accept(voucherDraft) }
  }
}
