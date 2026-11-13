package se.alipsa.accounting.mcp

/** EDT-safe bridge between MCP worker threads and the unsaved voucher editor. */
interface VoucherDraftAccess {
  /** Returns an empty map when there is no meaningful unsaved content. */
  Map<String, Object> getVoucherDraft()
  /** Refuses to discard unsaved content unless draft.replace_unsaved is explicitly true. */
  void setVoucherDraft(Map<String, Object> draft)
}
