package se.alipsa.accounting.ui

import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.Voucher
import se.alipsa.accounting.domain.VoucherLine
import se.alipsa.accounting.service.VoucherService
import se.alipsa.accounting.support.I18n

import java.time.LocalDate
import java.util.function.Consumer
import java.util.function.Supplier

/** Performs save and correction actions using state supplied by the voucher editor. */
final class VoucherEditorActions {

  private final VoucherService voucherService
  private final ActiveCompanyManager activeCompanyManager
  private final Supplier<LocalDate> dateSupplier
  private final Supplier<String> descriptionSupplier
  private final Supplier<List<VoucherLine>> linesSupplier
  private final Supplier<Voucher> currentVoucherSupplier
  private final Supplier<String> seriesSupplier
  private final Consumer<String> infoConsumer
  private final Consumer<String> errorConsumer
  private final Runnable reloadAction

  VoucherEditorActions(
      VoucherService voucherService,
      ActiveCompanyManager activeCompanyManager,
      Supplier<LocalDate> dateSupplier,
      Supplier<String> descriptionSupplier,
      Supplier<List<VoucherLine>> linesSupplier,
      Supplier<Voucher> currentVoucherSupplier,
      Supplier<String> seriesSupplier,
      Consumer<String> infoConsumer,
      Consumer<String> errorConsumer,
      Runnable reloadAction
  ) {
    this.voucherService = voucherService
    this.activeCompanyManager = activeCompanyManager
    this.dateSupplier = dateSupplier
    this.descriptionSupplier = descriptionSupplier
    this.linesSupplier = linesSupplier
    this.currentVoucherSupplier = currentVoucherSupplier
    this.seriesSupplier = seriesSupplier
    this.infoConsumer = infoConsumer
    this.errorConsumer = errorConsumer
    this.reloadAction = reloadAction
  }

  void save() {
    FiscalYear fiscalYear = activeCompanyManager.fiscalYear
    if (fiscalYear == null) {
      errorConsumer.accept(I18n.instance.getString('voucherPanel.error.noFiscalYear'))
      return
    }
    try {
      LocalDate date = dateSupplier.get()
      if (date == null) {
        errorConsumer.accept(I18n.instance.getString('voucherPanel.error.dateFormat'))
        return
      }
      if (currentVoucherSupplier.get() != null) {
        errorConsumer.accept(I18n.instance.getString('voucherPanel.error.existingVoucherReadOnly'))
        return
      }
      Voucher saved = voucherService.createVoucher(fiscalYear.id, seriesSupplier.get(), date, descriptionSupplier.get(), linesSupplier.get())
      infoConsumer.accept(I18n.instance.getString('voucherPanel.message.saved').replace('{0}', saved.voucherNumber ?: ''))
      reloadAction.run()
    } catch (Exception exception) {
      errorConsumer.accept(exception.message ?: I18n.instance.getString('voucherPanel.error.saveFailed'))
    }
  }

  void createCorrection() {
    Voucher currentVoucher = currentVoucherSupplier.get()
    if (currentVoucher == null) {
      return
    }
    try {
      Voucher correction = voucherService.createCorrectionVoucher(currentVoucher.id, null)
      infoConsumer.accept(I18n.instance.format('voucherPanel.message.correctionCreated', correction.voucherNumber ?: ''))
      reloadAction.run()
    } catch (Exception exception) {
      errorConsumer.accept(exception.message ?: I18n.instance.getString('voucherPanel.error.correctionFailed'))
    }
  }
}
