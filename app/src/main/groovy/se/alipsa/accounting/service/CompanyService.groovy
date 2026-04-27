package se.alipsa.accounting.service

import groovy.sql.GroovyRowResult
import groovy.sql.Sql

import se.alipsa.accounting.domain.Company
import se.alipsa.accounting.domain.CompanySettings
import se.alipsa.accounting.domain.VatPeriodicity

import java.util.logging.Logger

/**
 * Manages top-level company records for multi-company installations.
 */
final class CompanyService {

  private static final Logger LOG = Logger.getLogger(CompanyService.name)

  static final long LEGACY_COMPANY_ID = 1L

  private final DatabaseService databaseService
  private final AuditLogService auditLogService

  CompanyService(DatabaseService databaseService = DatabaseService.instance) {
    this(databaseService, new AuditLogService(databaseService))
  }

  CompanyService(DatabaseService databaseService, AuditLogService auditLogService) {
    this.databaseService = databaseService
    this.auditLogService = auditLogService
  }

  List<Company> listCompanies(boolean activeOnly = false) {
    databaseService.withSql { Sql sql ->
      StringBuilder query = new StringBuilder('''
          select id,
                 company_name as companyName,
                 organization_number as organizationNumber,
                 default_currency as defaultCurrency,
                 locale_tag as localeTag,
                 vat_periodicity as vatPeriodicity,
                 active,
                 created_at as createdAt,
                 updated_at as updatedAt,
                 archived
            from company
      ''')
      List<Object> params = []
      if (activeOnly) {
        query.append(' where active = true and archived = false')
      }
      query.append(' order by company_name, id')
      sql.rows(query.toString(), params).collect { GroovyRowResult row ->
        mapCompany(row)
      }
    }
  }

  Company findById(long companyId) {
    databaseService.withSql { Sql sql ->
      findById(sql, companyId)
    }
  }

  Company save(Company company) {
    validate(company)
    databaseService.withTransaction { Sql sql ->
      save(sql, company)
    }
  }

  List<Company> listArchivedCompanies() {
    databaseService.withSql { Sql sql ->
      sql.rows('''
          select id,
                 company_name as companyName,
                 organization_number as organizationNumber,
                 default_currency as defaultCurrency,
                 locale_tag as localeTag,
                 vat_periodicity as vatPeriodicity,
                 active,
                 created_at as createdAt,
                 updated_at as updatedAt,
                 archived
            from company
           where archived = true
           order by company_name, id
      ''').collect { GroovyRowResult row ->
        mapCompany(row)
      }
    }
  }

  Company archiveCompany(long companyId) {
    requireValidCompanyId(companyId)
    databaseService.withTransaction { Sql sql ->
      Company company = findById(sql, companyId)
      if (company == null) {
        throw new IllegalArgumentException("Okänt företags-id: ${companyId}")
      }
      sql.executeUpdate('''
          update company
             set archived = true,
                 active = false,
                 updated_at = current_timestamp
           where id = ?
      ''', [companyId])
      auditLogService.recordCompanyArchived(sql, companyId, company.companyName)
      findById(sql, companyId)
    }
  }

  Company unarchiveCompany(long companyId) {
    requireValidCompanyId(companyId)
    databaseService.withTransaction { Sql sql ->
      Company company = findById(sql, companyId)
      if (company == null) {
        throw new IllegalArgumentException("Okänt företags-id: ${companyId}")
      }
      sql.executeUpdate('''
          update company
             set archived = false,
                 active = true,
                 updated_at = current_timestamp
           where id = ?
      ''', [companyId])
      auditLogService.recordCompanyUnarchived(sql, companyId, company.companyName)
      findById(sql, companyId)
    }
  }

  void deleteCompany(long companyId) {
    requireValidCompanyId(companyId)
    databaseService.withTransaction { Sql sql ->
      Company company = findById(sql, companyId)
      if (company == null) {
        throw new IllegalArgumentException("Okänt företags-id: ${companyId}")
      }
      GroovyRowResult fyCount = sql.firstRow(
          'select count(*) as total from fiscal_year where company_id = ?',
          [companyId]
      ) as GroovyRowResult
      int total = ((Number) fyCount.get('total')).intValue()
      if (total > 0) {
        throw new IllegalStateException(
            "Företaget kan inte raderas eftersom det fortfarande har ${total} räkenskapsår."
        )
      }
      sql.executeUpdate('delete from audit_log where company_id = ?', [companyId])
      sql.executeUpdate('delete from audit_log_chain_head where company_id = ?', [companyId])
      sql.executeUpdate('delete from import_job where company_id = ?', [companyId])
      sql.executeUpdate('delete from account where company_id = ?', [companyId])
      int deleted = sql.executeUpdate('delete from company where id = ?', [companyId])
      if (deleted != 1) {
        throw new IllegalStateException("Företaget kunde inte raderas: ${companyId}")
      }
      LOG.info("Deleted company id=${companyId}, name='${company.companyName}'")
    }
  }

  Company upsertLegacyCompany(CompanySettings settings) {
    databaseService.withTransaction { Sql sql ->
      upsertLegacyCompany(sql, settings)
    }
  }

  Company upsertLegacyCompany(Sql sql, CompanySettings settings) {
    if (settings == null) {
      throw new IllegalArgumentException('Company settings are required.')
    }
    save(sql, new Company(
        LEGACY_COMPANY_ID,
        settings.companyName,
        settings.organizationNumber,
        settings.defaultCurrency,
        settings.localeTag,
        settings.vatPeriodicity ?: VatPeriodicity.MONTHLY,
        true,
        null,
        null
    ))
  }

  Company save(Sql sql, Company company) {
    validate(company)
    if (company.id == null) {
      create(sql, company)
    } else {
      update(sql, company)
    }
  }

  private Company create(Sql sql, Company company) {
    List<List<Object>> keys = sql.executeInsert('''
        insert into company (
            company_name,
            organization_number,
            default_currency,
            locale_tag,
            vat_periodicity,
            active,
            archived,
            created_at,
            updated_at
        ) values (?, ?, ?, ?, ?, ?, ?, current_timestamp, current_timestamp)
    ''', [
        company.companyName.trim(),
        company.organizationNumber.trim(),
        company.defaultCurrency.trim().toUpperCase(Locale.ROOT),
        company.localeTag.trim(),
        (company.vatPeriodicity ?: VatPeriodicity.MONTHLY).name(),
        company.active,
        company.archived
    ])
    long companyId = ((Number) keys.first().first()).longValue()
    sql.executeInsert('''
        insert into audit_log_chain_head (company_id, last_entry_hash, updated_at)
        values (?, null, current_timestamp)
    ''', [companyId])
    findById(sql, companyId)
  }

  private Company update(Sql sql, Company company) {
    int updated = sql.executeUpdate('''
        update company
           set company_name = ?,
               organization_number = ?,
               default_currency = ?,
               locale_tag = ?,
               vat_periodicity = ?,
               active = ?,
               archived = ?,
               updated_at = current_timestamp
         where id = ?
    ''', [
        company.companyName.trim(),
        company.organizationNumber.trim(),
        company.defaultCurrency.trim().toUpperCase(Locale.ROOT),
        company.localeTag.trim(),
        (company.vatPeriodicity ?: VatPeriodicity.MONTHLY).name(),
        company.active,
        company.archived,
        company.id
    ])
    if (updated != 1) {
      throw new IllegalArgumentException("Unknown company id: ${company.id}")
    }
    findById(sql, company.id)
  }

  static void requireValidCompanyId(long companyId) {
    if (companyId <= 0) {
      throw new IllegalArgumentException("Ogiltigt företags-id: ${companyId}")
    }
  }

  static long resolveFromFiscalYear(Sql sql, long fiscalYearId) {
    GroovyRowResult row = sql.firstRow(
        'select company_id as companyId from fiscal_year where id = ?',
        [fiscalYearId]
    ) as GroovyRowResult
    if (row == null) {
      throw new IllegalArgumentException("Okänt räkenskapsår: ${fiscalYearId}")
    }
    ((Number) row.get('companyId')).longValue()
  }

  long resolveFromFiscalYear(long fiscalYearId) {
    databaseService.withSql { Sql sql ->
      resolveFromFiscalYear(sql, fiscalYearId)
    }
  }

  private static Company findById(Sql sql, long companyId) {
    GroovyRowResult row = sql.firstRow('''
        select id,
               company_name as companyName,
               organization_number as organizationNumber,
               default_currency as defaultCurrency,
               locale_tag as localeTag,
               vat_periodicity as vatPeriodicity,
               active,
               created_at as createdAt,
               updated_at as updatedAt,
               archived
          from company
         where id = ?
    ''', [companyId]) as GroovyRowResult
    row == null ? null : mapCompany(row)
  }

  private static Company mapCompany(GroovyRowResult row) {
    new Company(
        Long.valueOf(row.get('id').toString()),
        row.get('companyName') as String,
        row.get('organizationNumber') as String,
        row.get('defaultCurrency') as String,
        row.get('localeTag') as String,
        VatPeriodicity.fromDatabaseValue(row.get('vatPeriodicity') as String),
        Boolean.TRUE == row.get('active'),
        SqlValueMapper.toLocalDateTime(row.get('createdAt')),
        SqlValueMapper.toLocalDateTime(row.get('updatedAt')),
        Boolean.TRUE == row.get('archived')
    )
  }

  private static void validate(Company company) {
    if (company == null) {
      throw new IllegalArgumentException('Company is required.')
    }
    if (!company.companyName?.trim()) {
      throw new IllegalArgumentException('Company name is required.')
    }
    if (!company.organizationNumber?.trim()) {
      throw new IllegalArgumentException('Organization number is required.')
    }
    if (!company.defaultCurrency?.trim()) {
      throw new IllegalArgumentException('Default currency is required.')
    }
    if (!company.localeTag?.trim()) {
      throw new IllegalArgumentException('Locale tag is required.')
    }
    if (company.vatPeriodicity == null) {
      throw new IllegalArgumentException('VAT periodicity is required.')
    }
  }
}
