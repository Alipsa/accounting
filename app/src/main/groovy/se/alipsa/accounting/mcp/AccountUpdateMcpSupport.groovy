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
    long companyId = requiredLong(args, 'company_id')
    String accountNumber = requiredString(args, 'account_number')
    String accountClass = requiredString(args, 'account_class').toUpperCase(Locale.ROOT)
    String normalBalanceSide = requiredString(args, 'normal_balance_side').toUpperCase(Locale.ROOT)
    try {
      if (!AccountService.isKnownAccountClass(accountClass)) {
        return [ok: false, errors: ["Okänd kontoklass: ${accountClass}".toString()]]
      }
      if (!AccountService.isKnownNormalBalanceSide(normalBalanceSide)) {
        return [ok: false, errors: ["Okänd normalsida: ${normalBalanceSide}".toString()]]
      }
      Account account = accountService.findAccount(companyId, accountNumber)
      if (account == null) {
        return [ok: false, errors: ["Okänt kontonummer: ${accountNumber}".toString()]]
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
        warnings << ("Kontot har bokförda transaktioner i ${usage.closedFiscalYearCount} stängda räkenskapsår; " +
            'omklassificering ändrar rapporterna för dessa år i efterhand.' as String)
      }
      [
          ok: valid,
          errors: errors,
          warnings: warnings,
          unchanged: unchanged,
          account: accountMap(account),
          proposed: [
              account_class: accountClass,
              normal_balance_side: normalBalanceSide
          ],
          usage: [
              voucher_count: usage.voucherCount,
              voucher_line_count: usage.voucherLineCount,
              fiscal_year_count: usage.fiscalYearCount,
              closed_fiscal_year_count: usage.closedFiscalYearCount
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
    long companyId = requiredLong(args, 'company_id')
    String accountNumber = requiredString(args, 'account_number')
    String accountClass = requiredString(args, 'account_class').toUpperCase(Locale.ROOT)
    String normalBalanceSide = requiredString(args, 'normal_balance_side').toUpperCase(Locale.ROOT)
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
      [ok: true, account: accountMap(updated)]
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

  private static Map<String, Object> accountMap(Account account) {
    [
        id: account.id,
        account_number: account.accountNumber,
        account_name: account.accountName,
        account_class: account.accountClass,
        normal_balance_side: account.normalBalanceSide,
        vat_code: account.vatCode,
        active: account.active,
        manual_review_required: account.manualReviewRequired,
        classification_note: account.classificationNote,
        account_subgroup: account.accountSubgroup
    ]
  }

  private static long requiredLong(Map<String, Object> args, String key) {
    Object value = args.get(key)
    if (value == null) {
      throw new IllegalArgumentException("Missing required argument: ${key}")
    }
    if (!(value instanceof Number)) {
      throw new IllegalArgumentException("Argument ${key} must be a number.")
    }
    ((Number) value).longValue()
  }

  private static String requiredString(Map<String, Object> args, String key) {
    String value = args.get(key) as String
    if (!value?.trim()) {
      throw new IllegalArgumentException("Missing required argument: ${key}")
    }
    value.trim()
  }
}
