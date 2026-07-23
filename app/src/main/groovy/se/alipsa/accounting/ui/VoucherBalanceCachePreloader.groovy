package se.alipsa.accounting.ui

import se.alipsa.accounting.domain.Voucher
import se.alipsa.accounting.domain.VoucherLine
import se.alipsa.accounting.domain.VoucherStatus
import se.alipsa.accounting.service.AccountService

import java.util.function.BiConsumer
import java.util.logging.Logger

import javax.swing.SwingWorker

/**
 * Loads historical voucher balances without blocking the voucher editor.
 */
final class VoucherBalanceCachePreloader {

  private static final Logger log = Logger.getLogger(VoucherBalanceCachePreloader.name)

  private final AccountService accountService

  VoucherBalanceCachePreloader(AccountService accountService) {
    this.accountService = accountService
  }

  void preload(
      long companyId,
      long fiscalYearId,
      List<Voucher> vouchers,
      int cacheGeneration,
      BiConsumer<Map<Long, Map<String, BigDecimal>>, Integer> cacheConsumer
  ) {
    Set<String> accountNumbers = vouchers
        .collectMany { Voucher voucher -> voucher.lines }
        .collect { VoucherLine line -> line.accountNumber }
        .findAll { String accountNumber -> hasText(accountNumber) } as Set<String>
    if (accountNumbers.isEmpty()) {
      return
    }
    new SwingWorker<Map<Long, Map<String, BigDecimal>>, Void>() {
      @Override
      protected Map<Long, Map<String, BigDecimal>> doInBackground() {
        Map<String, BigDecimal> endingBalances = accountService.calculateAccountBalances(
            companyId, fiscalYearId, accountNumbers)
        Map<String, String> normalBalanceSides = accountService.normalBalanceSides(companyId, accountNumbers)
        Map<Long, Map<String, BigDecimal>> preloadedBalances = [:]
        Map<String, BigDecimal> laterVoucherChanges = [:]
        vouchers.reverseEach { Voucher voucher ->
          Map<String, BigDecimal> voucherBalances = [:]
          Map<String, BigDecimal> voucherChanges = [:]
          if (voucher.status == VoucherStatus.ACTIVE || voucher.status == VoucherStatus.CORRECTION) {
            voucher.lines.each { VoucherLine line ->
              String accountNumber = line.accountNumber
              String normalBalanceSide = normalBalanceSides[accountNumber]
              BigDecimal change = 'CREDIT' == normalBalanceSide
                  ? (line.creditAmount ?: BigDecimal.ZERO).subtract(line.debitAmount ?: BigDecimal.ZERO)
                  : (line.debitAmount ?: BigDecimal.ZERO).subtract(line.creditAmount ?: BigDecimal.ZERO)
              voucherChanges[accountNumber] = (voucherChanges[accountNumber] ?: BigDecimal.ZERO).add(change)
            }
          }
          voucher.lines.each { VoucherLine line ->
            BigDecimal endingBalance = endingBalances[line.accountNumber]
            if (endingBalance != null) {
              voucherBalances[line.accountNumber] = endingBalance
                  .subtract(laterVoucherChanges[line.accountNumber] ?: BigDecimal.ZERO)
                  .subtract(voucherChanges[line.accountNumber] ?: BigDecimal.ZERO)
            }
          }
          preloadedBalances[voucher.id] = voucherBalances
          voucherChanges.each { String accountNumber, BigDecimal change ->
            laterVoucherChanges[accountNumber] = (laterVoucherChanges[accountNumber] ?: BigDecimal.ZERO).add(change)
          }
        }
        preloadedBalances
      }

      @Override
      protected void done() {
        if (isCancelled()) {
          return
        }
        try {
          cacheConsumer.accept(get(), cacheGeneration)
        } catch (Exception ex) {
          log.fine("Could not pre-populate voucher balance cache: ${ex.message}")
        }
      }
    }.execute()
  }

  private static boolean hasText(String value) {
    value != null && !value.isBlank()
  }
}
