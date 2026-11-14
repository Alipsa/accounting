package se.alipsa.accounting.mcp

import se.alipsa.accounting.domain.Account
import se.alipsa.accounting.domain.VatCode
import se.alipsa.accounting.service.AccountService

import java.util.concurrent.ConcurrentHashMap

/**
 * Backs the preview_account_update and update_account MCP tools. Previews a classification
 * change with its blast radius (including a warning when the account is used in closed fiscal
 * years, since reclassification restates their reports), issues single-use preview tokens bound
 * to the account's current state and per-account write sequence, and applies the change through
 * the account service.
 */
final class AccountUpdateMcpSupport {

  private final AccountService accountService
  private final PreviewTokenLedger previewTokenLedger = new PreviewTokenLedger()
  private final Map<String, Integer> accountUpdateSequences = new ConcurrentHashMap<>()

  AccountUpdateMcpSupport(AccountService accountService) {
    this.accountService = accountService
  }

  Map<String, Object> preview(Map<String, Object> args) {
    long companyId = McpArgs.requiredLong(args, 'company_id')
    String accountNumber = McpArgs.requiredString(args, 'account_number')
    String accountClass = McpArgs.requiredString(args, 'account_class').toUpperCase(Locale.ROOT)
    String normalBalanceSide = McpArgs.requiredString(args, 'normal_balance_side').toUpperCase(Locale.ROOT)
    try {
      if (!AccountService.isKnownAccountClass(accountClass)) {
        return [ok: false, errors: ["Okänd kontoklass: ${accountClass}".toString()], warnings: []]
      }
      if (!AccountService.isKnownNormalBalanceSide(normalBalanceSide)) {
        return [ok: false, errors: ["Okänd normalsida: ${normalBalanceSide}".toString()], warnings: []]
      }
      Account account = accountService.findAccount(companyId, accountNumber)
      if (account == null) {
        return [ok: false, errors: ["Okänt kontonummer: ${accountNumber}".toString()], warnings: []]
      }
      List<String> errors = []
      if (account.vatCode) {
        VatCode currentVatCode = AccountService.parseVatCode(account.vatCode)
        if (currentVatCode == null) {
          errors.add("Kontots momskod ${account.vatCode} är okänd och kan inte kombineras med kontoklass ${accountClass}.".toString())
        } else if (!AccountService.isVatCompatible(new Account(accountClass: accountClass), currentVatCode)) {
          errors.add(
              "Kontoklass ${accountClass} är inte kompatibelt med kontots momskod ${account.vatCode}.".toString()
          )
        }
      }
      AccountService.AccountUsageSummary usage = accountService.summarizeAccountUsage(companyId, accountNumber)
      boolean unchanged = account.accountClass == accountClass &&
          account.normalBalanceSide == normalBalanceSide &&
          !account.manualReviewRequired &&
          !account.classificationNote
      boolean valid = errors.isEmpty()
      List<String> warnings = []
      if (usage.closedFiscalYearCount > 0) {
        String years = usage.closedFiscalYearCount == 1 ? 'stängt räkenskapsår' : 'stängda räkenskapsår'
        warnings << ("Kontot har bokförda transaktioner i ${usage.closedFiscalYearCount} ${years}; " +
            'omklassificering ändrar rapporterna för dessa år i efterhand.' as String)
      }
      if (account.normalBalanceSide != normalBalanceSide && usage.openingBalanceYearCount > 0) {
        warnings << ("Byte av normalsida vänder tecknet på kontots ingående balanser i " +
            "${usage.openingBalanceYearCount} räkenskapsår." as String)
      }
      [
          ok: valid,
          errors: errors,
          warnings: warnings,
          unchanged: unchanged,
          account: AccountingMcpTools.accountMap(account),
          proposed: [
              account_class: accountClass,
              normal_balance_side: normalBalanceSide
          ],
          usage: [
              voucher_count: usage.voucherCount,
              voucher_line_count: usage.voucherLineCount,
              fiscal_year_count: usage.fiscalYearCount,
              closed_fiscal_year_count: usage.closedFiscalYearCount,
              opening_balance_year_count: usage.openingBalanceYearCount
          ],
          // No token when nothing would change; re-applying an already-used state must not
          // return the identical, consumed token. The token binds the account's current state
          // and its per-account write sequence, so a change made elsewhere after the preview
          // invalidates it and no state sequence can ever repeat a consumed token.
          preview_token: valid && !unchanged
              ? accountUpdateToken(companyId, accountNumber, accountClass, normalBalanceSide, account)
              : null
      ]
    } catch (Exception exception) {
      [ok: false, errors: [exception.message ?: exception.class.simpleName], warnings: []]
    }
  }

  Map<String, Object> apply(Map<String, Object> args) {
    long companyId = McpArgs.requiredLong(args, 'company_id')
    String accountNumber = McpArgs.requiredString(args, 'account_number')
    String accountClass = McpArgs.requiredString(args, 'account_class').toUpperCase(Locale.ROOT)
    String normalBalanceSide = McpArgs.requiredString(args, 'normal_balance_side').toUpperCase(Locale.ROOT)
    String providedToken = args.get('preview_token') as String
    if (!providedToken) {
      return [ok: false, errors: ['preview_token krävs — kör preview_account_update först.']]
    }
    String token = "account-update:${providedToken}"
    try {
      Account current = accountService.findAccount(companyId, accountNumber)
      if (current == null) {
        return [ok: false, errors: ["Okänt kontonummer: ${accountNumber}".toString()]]
      }
      String expectedToken = accountUpdateToken(companyId, accountNumber, accountClass, normalBalanceSide, current)
      if (providedToken != expectedToken) {
        return [ok: false, errors: ['preview_token matchar inte kontot eller de förhandsgranskade värdena — kör preview_account_update igen.']]
      }
      if (!previewTokenLedger.reserve(token)) {
        return [ok: false, errors: ['preview_token har redan använts — kör preview_account_update igen.']]
      }
      Account updated = (Account) previewTokenLedger.executeReservedWrite(token) {
        accountService.updateAccountClassification(companyId, accountNumber, accountClass, normalBalanceSide)
      }
      accountUpdateSequences.merge(accountUpdateSequenceKey(companyId, accountNumber), 1) { Integer oldValue, Integer newValue ->
        oldValue + newValue
      }
      [ok: true, account: AccountingMcpTools.accountMap(updated)]
    } catch (Exception exception) {
      [ok: false, errors: [exception.message ?: exception.class.simpleName]]
    }
  }

  private String accountUpdateToken(
      long companyId,
      String accountNumber,
      String accountClass,
      String normalBalanceSide,
      Account currentState
  ) {
    AccountingMcpTools.computePreviewToken([
        company_id: companyId,
        account_number: accountNumber,
        account_class: accountClass,
        normal_balance_side: normalBalanceSide,
        // Bind the token to the account's current state so an outdated preview (changed
        // elsewhere after previewing) no longer matches. The per-account write sequence makes
        // the token strictly increasing: it is raised on every successful MCP account update,
        // so even when the account returns to an earlier state (A->B->A->B) the token never
        // repeats a consumed one - while writes to other accounts or unrelated audit entries
        // do not invalidate it.
        current_account_class: currentState?.accountClass,
        current_normal_balance_side: currentState?.normalBalanceSide,
        vat_code: currentState?.vatCode,
        manual_review_required: currentState?.manualReviewRequired,
        update_sequence: accountUpdateSequence(companyId, accountNumber)
    ])
  }

  private int accountUpdateSequence(long companyId, String accountNumber) {
    accountUpdateSequences.getOrDefault(accountUpdateSequenceKey(companyId, accountNumber), 0)
  }

  private static String accountUpdateSequenceKey(long companyId, String accountNumber) {
    "${companyId}:${accountNumber}"
  }
}
