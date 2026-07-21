package se.alipsa.accounting.mcp

/** EDT-safe bridge between MCP worker threads and the unsaved voucher editor. */
interface VoucherDraftAccess {
  Map<String, Object> getVoucherDraft()
  void setVoucherDraft(Map<String, Object> draft)
}
