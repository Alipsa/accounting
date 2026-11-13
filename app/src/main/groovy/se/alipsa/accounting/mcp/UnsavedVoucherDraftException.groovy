package se.alipsa.accounting.mcp

/** A state conflict, distinct from invalid MCP arguments. */
final class UnsavedVoucherDraftException extends IllegalStateException {

  UnsavedVoucherDraftException() {
    super('The voucher editor has unsaved changes. Save or clear them first, or pass replace_unsaved: true.')
  }
}
