package se.alipsa.accounting.ui

import se.alipsa.accounting.domain.Voucher

import java.util.function.Consumer

/** Keeps voucher navigation state separate from the editor's Swing controls. */
final class VoucherNavigation {

  private List<Voucher> vouchers = []
  private int currentIndex = -1
  private VoucherDraftMapper.VoucherDraft draft

  void reset(List<Voucher> values) {
    vouchers = values
    currentIndex = -1
    draft = null
  }

  List<Voucher> getVouchers() {
    vouchers
  }

  boolean isOnDraft() {
    currentIndex < 0
  }

  void rememberDraft(VoucherDraftMapper.VoucherDraft value) {
    if (isOnDraft()) {
      draft = value
    }
  }

  void clearDraft() {
    draft = null
  }

  void showDraft() {
    currentIndex = -1
  }

  VoucherDraftMapper.VoucherDraft draft() {
    draft
  }

  void previous(Consumer<Voucher> voucherConsumer) {
    if (vouchers.isEmpty()) {
      return
    }
    currentIndex = currentIndex < 0 ? vouchers.size() - 1 : Math.max(0, currentIndex - 1)
    voucherConsumer.accept(vouchers[currentIndex])
  }

  void next(Consumer<Voucher> voucherConsumer, Runnable draftConsumer) {
    if (currentIndex < 0) {
      return
    }
    if (currentIndex >= vouchers.size() - 1) {
      currentIndex = -1
      draftConsumer.run()
    } else {
      currentIndex++
      voucherConsumer.accept(vouchers[currentIndex])
    }
  }

  void first(Consumer<Voucher> voucherConsumer) {
    select(0, voucherConsumer)
  }

  void last(Consumer<Voucher> voucherConsumer) {
    select(vouchers.size() - 1, voucherConsumer)
  }

  void select(Voucher voucher, Consumer<Voucher> voucherConsumer) {
    int index = vouchers.findIndexOf { Voucher candidate -> candidate.id == voucher.id }
    if (index >= 0) {
      select(index, voucherConsumer)
    }
  }

  boolean canGoPrevious() {
    !vouchers.isEmpty() && currentIndex != 0
  }

  boolean canGoNext() {
    !vouchers.isEmpty() && currentIndex >= 0
  }

  boolean canGoLast() {
    !vouchers.isEmpty() && currentIndex != vouchers.size() - 1
  }

  private void select(int index, Consumer<Voucher> voucherConsumer) {
    if (vouchers.isEmpty()) {
      return
    }
    currentIndex = index
    voucherConsumer.accept(vouchers[currentIndex])
  }
}
