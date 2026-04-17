package se.alipsa.accounting.service

import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.transform.Canonical

import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.usermodel.WorkbookFactory

import se.alipsa.accounting.domain.Account
import se.alipsa.accounting.domain.AccountSubgroup

import java.nio.file.Files
import java.nio.file.Path

/**
 * Imports BAS chart of accounts from Excel using Apache POI.
 */
final class ChartOfAccountsImportService {

  private static final Set<String> INCOME_KEYWORDS = [
      'INTAKT', 'RANTEINTAKT', 'RANTEINTAKTER', 'VINST', 'ERHALL', 'ERHALLNA', 'ERHALLET', 'UTDELNING',
      'BIDRAG', 'OVERSKOTT', 'ATERFORING', 'RABATT'
  ] as Set<String>
  private static final Set<String> EXPENSE_KEYWORDS = [
      'KOSTNAD', 'KOSTNADER', 'FORLUST', 'FORLUSTER', 'RANTEKOSTNAD',
      'RANTEKOSTNADER', 'SKATT', 'NEDSKRIVNING', 'NEDSKRIVNINGAR',
      'AVSATTNING', 'LAMNADE', 'UTGIFT', 'UTGIFTER'
  ] as Set<String>

  private final DatabaseService databaseService

  ChartOfAccountsImportService(DatabaseService databaseService = DatabaseService.instance) {
    this.databaseService = databaseService
  }

  ImportSummary importFromExcel(Path workbookPath) {
    validateWorkbookPath(workbookPath)
    List<Account> accounts = parseWorkbook(workbookPath)
    databaseService.withTransaction { Sql sql ->
      persistAccounts(sql, accounts)
    }
  }

  private static void validateWorkbookPath(Path workbookPath) {
    if (workbookPath == null) {
      throw new IllegalArgumentException('A workbook path is required.')
    }
    if (!Files.exists(workbookPath)) {
      throw new IllegalArgumentException("Workbook not found: ${workbookPath}")
    }
  }

  private ImportSummary persistAccounts(Sql sql, List<Account> accounts) {
    long companyId = resolveDefaultCompanyId(sql)
    int created = 0
    int updated = 0

    accounts.each { Account account ->
      GroovyRowResult existing = sql.firstRow(
          'select account_number from account where company_id = ? and account_number = ?',
          [companyId, account.accountNumber]
      ) as GroovyRowResult
      if (existing == null) {
        sql.executeInsert('''
            insert into account (
                company_id,
                account_number,
                account_name,
                account_class,
                normal_balance_side,
                vat_code,
                active,
                manual_review_required,
                classification_note,
                account_subgroup,
                created_at,
                updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp, current_timestamp)
        ''', [
            companyId,
            account.accountNumber,
            account.accountName,
            account.accountClass,
            account.normalBalanceSide,
            account.vatCode,
            account.active,
            account.manualReviewRequired,
            account.classificationNote,
            account.accountSubgroup
        ])
        created++
      } else {
        // Re-imports refresh BAS-derived metadata while preserving user-maintained flags like active and vat_code.
        sql.executeUpdate('''
            update account
               set account_name = ?,
                   account_class = ?,
                   normal_balance_side = ?,
                   manual_review_required = ?,
                   classification_note = ?,
                   account_subgroup = ?,
                   updated_at = current_timestamp
             where company_id = ?
               and account_number = ?
        ''', [
            account.accountName,
            account.accountClass,
            account.normalBalanceSide,
            account.manualReviewRequired,
            account.classificationNote,
            account.accountSubgroup,
            companyId,
            account.accountNumber
        ])
        updated++
      }
    }

    int manualReviewCount = accounts.count { Account account -> account.manualReviewRequired } as int
    new ImportSummary(
        accounts.size(),
        created,
        updated,
        manualReviewCount
    )
  }

  private static List<Account> parseWorkbook(Path workbookPath) {
    Map<String, Account> accounts = [:]
    DataFormatter formatter = new DataFormatter(Locale.ROOT)

    Files.newInputStream(workbookPath).withCloseable { InputStream input ->
      Workbook workbook = WorkbookFactory.create(input)
      try {
        Sheet sheet = workbook.getSheetAt(0)
        for (Row row : sheet) {
          addAccountIfPresent(accounts, formatter, row, 0, 1)
          addAccountIfPresent(accounts, formatter, row, 2, 3)
        }
      } finally {
        workbook.close()
      }
    }

    accounts.values() as List<Account>
  }

  private static void addAccountIfPresent(
      Map<String, Account> accounts,
      DataFormatter formatter,
      Row row,
      int accountColumn,
      int nameColumn
  ) {
    String accountNumber = normalizeAccountNumber(formatter.formatCellValue(row.getCell(accountColumn)))
    if (!accountNumber) {
      return
    }

    String accountName = normalizeAccountName(formatter.formatCellValue(row.getCell(nameColumn)))
    Classification classification = classifyAccount(accountNumber, accountName)
    accounts[accountNumber] = new Account(
        accountNumber: accountNumber,
        accountName: accountName,
        accountClass: classification.accountClass,
        normalBalanceSide: classification.normalBalanceSide,
        vatCode: null,
        active: true,
        manualReviewRequired: classification.manualReviewRequired,
        classificationNote: classification.note,
        accountSubgroup: classification.accountSubgroup
    )
  }

  private static String normalizeAccountNumber(String value) {
    String normalized = (value ?: '')
        .replace('#', '')
        .replaceAll(/\s+/, '')
        .trim()
    normalized ==~ /\d{4}/ ? normalized : ''
  }

  private static String normalizeAccountName(String value) {
    (value ?: '')
        .replaceAll(/\s+/, ' ')
        .trim()
  }

  private static Classification classifyAccount(String accountNumber, String accountName) {
    int prefix = Integer.parseInt(accountNumber.substring(0, 1))
    int subgroup = Integer.parseInt(accountNumber.substring(0, 2))
    String accountSubgroup = AccountSubgroup.fromAccountNumber(accountNumber)?.name()

    switch (prefix) {
      case 1:
        return new Classification('ASSET', 'DEBIT', false, null, accountSubgroup)
      case 2:
        if (subgroup <= 20) {
          return new Classification('EQUITY', 'CREDIT', false, null, accountSubgroup)
        }
        return new Classification('LIABILITY', 'CREDIT', false, null, accountSubgroup)
      case 3:
        return new Classification('INCOME', 'CREDIT', false, null, accountSubgroup)
      case 4:
      case 5:
      case 6:
      case 7:
        return new Classification('EXPENSE', 'DEBIT', false, null, accountSubgroup)
      case 8:
        return classifyMixedResultAccount(accountNumber, accountName)
      default:
        return new Classification(
            null,
            null,
            true,
            'Kontot kunde inte klassificeras automatiskt från BAS-importen.',
            accountSubgroup
        )
    }
  }

  private static Classification classifyMixedResultAccount(String accountNumber, String accountName) {
    String accountSubgroup = AccountSubgroup.fromAccountNumber(accountNumber)?.name()
    String normalized = stripDiacritics(accountName).toUpperCase(Locale.ROOT)
    boolean incomeMatch = INCOME_KEYWORDS.any { String keyword -> normalized.contains(keyword) }
    boolean expenseMatch = EXPENSE_KEYWORDS.any { String keyword -> normalized.contains(keyword) }

    if (incomeMatch && !expenseMatch) {
      return new Classification('INCOME', 'CREDIT', false, null, accountSubgroup)
    }
    if (expenseMatch && !incomeMatch) {
      return new Classification('EXPENSE', 'DEBIT', false, null, accountSubgroup)
    }

    new Classification(
        null,
        null,
        true,
        'Kontot kräver manuell klassning eftersom BAS-gruppen innehåller både intäkter och kostnader.',
        accountSubgroup
    )
  }

  private static String stripDiacritics(String value) {
    if (!value) {
      return ''
    }
    String normalized = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
    normalized.replaceAll(/\p{M}+/, '')
  }

  private static long resolveDefaultCompanyId(Sql sql) {
    GroovyRowResult row = sql.firstRow(
        'select id from company order by id limit 1'
    ) as GroovyRowResult
    if (row == null) {
      throw new IllegalStateException('Inget företag finns i databasen.')
    }
    ((Number) row.get('id')).longValue()
  }

  @Canonical
  static final class ImportSummary {

    int importedCount
    int createdCount
    int updatedCount
    int manualReviewCount
  }

  @Canonical
  private static final class Classification {

    String accountClass
    String normalBalanceSide
    boolean manualReviewRequired
    String note
    String accountSubgroup
  }
}
