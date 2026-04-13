package se.alipsa.accounting.service

import groovy.sql.GroovyRowResult
import groovy.sql.Sql

import se.alipsa.accounting.domain.Company
import se.alipsa.accounting.domain.CompanySettings
import se.alipsa.accounting.domain.VatPeriodicity

/**
 * Manages top-level company records for multi-company installations.
 */
final class CompanyService {

  static final long LEGACY_COMPANY_ID = 1L

  private final DatabaseService databaseService

  CompanyService(DatabaseService databaseService = DatabaseService.instance) {
    this.databaseService = databaseService
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
                 updated_at as updatedAt
            from company
      ''')
      List<Object> params = []
      if (activeOnly) {
        query.append(' where active = true')
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
            created_at,
            updated_at
        ) values (?, ?, ?, ?, ?, ?, current_timestamp, current_timestamp)
    ''', [
        company.companyName.trim(),
        company.organizationNumber.trim(),
        company.defaultCurrency.trim().toUpperCase(Locale.ROOT),
        company.localeTag.trim(),
        (company.vatPeriodicity ?: VatPeriodicity.MONTHLY).name(),
        company.active
    ])
    long companyId = ((Number) keys.first().first()).longValue()
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
               updated_at = current_timestamp
         where id = ?
    ''', [
        company.companyName.trim(),
        company.organizationNumber.trim(),
        company.defaultCurrency.trim().toUpperCase(Locale.ROOT),
        company.localeTag.trim(),
        (company.vatPeriodicity ?: VatPeriodicity.MONTHLY).name(),
        company.active,
        company.id
    ])
    if (updated != 1) {
      throw new IllegalArgumentException("Unknown company id: ${company.id}")
    }
    findById(sql, company.id)
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
               updated_at as updatedAt
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
        SqlValueMapper.toLocalDateTime(row.get('updatedAt'))
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
