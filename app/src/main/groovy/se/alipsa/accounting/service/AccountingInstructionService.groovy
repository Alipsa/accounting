package se.alipsa.accounting.service

import groovy.sql.GroovyRowResult
import groovy.sql.Sql

import se.alipsa.accounting.domain.AccountingInstruction

import java.sql.Timestamp
import java.time.LocalDateTime

/** Stores company-approved instructions for recurring accounting events. */
final class AccountingInstructionService {

  private final DatabaseService databaseService
  private final AccountService accountService

  AccountingInstructionService(DatabaseService databaseService = DatabaseService.instance) {
    this.databaseService = databaseService
    this.accountService = new AccountService(databaseService)
  }

  List<AccountingInstruction> list(long companyId) {
    CompanyService.requireValidCompanyId(companyId)
    databaseService.withSql { Sql sql ->
      sql.rows('''
          select id, company_id as companyId, trigger_text as triggerText, description,
                 debit_account_number as debitAccountNumber, credit_account_number as creditAccountNumber,
                 series_code as seriesCode, created_at as createdAt, updated_at as updatedAt
            from accounting_instruction
           where company_id = ?
           order by trigger_text, id
      ''', [companyId]).collect { GroovyRowResult row -> map(row) }
    }
  }

  AccountingInstruction save(
      long companyId,
      String triggerText,
      String description,
      String debitAccountNumber,
      String creditAccountNumber,
      String seriesCode
  ) {
    CompanyService.requireValidCompanyId(companyId)
    String trigger = required(triggerText, 'trigger_text')
    String debit = required(debitAccountNumber, 'debit_account_number')
    String credit = required(creditAccountNumber, 'credit_account_number')
    if (debit == credit) {
      throw new IllegalArgumentException('Debit and credit accounts must differ.')
    }
    requireActiveAccount(companyId, debit)
    requireActiveAccount(companyId, credit)
    String series = seriesCode?.trim() ?: 'A'
    databaseService.withTransaction { Sql sql ->
      GroovyRowResult existing = sql.firstRow(
          'select id from accounting_instruction where company_id = ? and trigger_text = ?', [companyId, trigger]) as GroovyRowResult
      if (existing == null) {
        List<List<Object>> keys = sql.executeInsert('''
            insert into accounting_instruction (
                company_id, trigger_text, description, debit_account_number, credit_account_number,
                series_code, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, current_timestamp, current_timestamp)
        ''', [companyId, trigger, description?.trim(), debit, credit, series])
        find(sql, ((Number) keys.first().first()).longValue())
      } else {
        sql.executeUpdate('''
            update accounting_instruction
               set description = ?, debit_account_number = ?, credit_account_number = ?, series_code = ?,
                   updated_at = current_timestamp
             where id = ?
        ''', [description?.trim(), debit, credit, series, ((Number) existing.id).longValue()])
        find(sql, ((Number) existing.id).longValue())
      }
    }
  }

  private void requireActiveAccount(long companyId, String accountNumber) {
    if (accountService.findAccount(companyId, accountNumber)?.active != true) {
      throw new IllegalArgumentException("Active account ${accountNumber} was not found for this company.")
    }
  }

  private static String required(String value, String field) {
    String result = value?.trim()
    if (!result) {
      throw new IllegalArgumentException("${field} is required.")
    }
    result
  }

  private static AccountingInstruction find(Sql sql, long id) {
    map(sql.firstRow('''
        select id, company_id as companyId, trigger_text as triggerText, description,
               debit_account_number as debitAccountNumber, credit_account_number as creditAccountNumber,
               series_code as seriesCode, created_at as createdAt, updated_at as updatedAt
          from accounting_instruction where id = ?
    ''', [id]) as GroovyRowResult)
  }

  private static AccountingInstruction map(GroovyRowResult row) {
    new AccountingInstruction(
        ((Number) row.id).longValue(),
        ((Number) row.companyId).longValue(),
        row.triggerText as String,
        row.description as String,
        row.debitAccountNumber as String,
        row.creditAccountNumber as String,
        row.seriesCode as String,
        toLocalDateTime(row.createdAt),
        toLocalDateTime(row.updatedAt)
    )
  }

  private static LocalDateTime toLocalDateTime(Object value) {
    value instanceof Timestamp ? ((Timestamp) value).toLocalDateTime() : (LocalDateTime) value
  }
}
