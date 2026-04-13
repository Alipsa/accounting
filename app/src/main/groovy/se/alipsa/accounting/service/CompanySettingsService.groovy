package se.alipsa.accounting.service

import groovy.sql.GroovyRowResult
import groovy.sql.Sql

import se.alipsa.accounting.domain.CompanySettings
import se.alipsa.accounting.domain.VatPeriodicity

/**
 * Persists and loads the single company configuration for the installation.
 */
final class CompanySettingsService {

  private static final long SETTINGS_ID = 1L

  private final DatabaseService databaseService

  CompanySettingsService(DatabaseService databaseService = DatabaseService.instance) {
    this.databaseService = databaseService
  }

  CompanySettings getSettings() {
    databaseService.withSql { Sql sql ->
      loadSettings(sql)
    }
  }

  boolean isConfigured() {
    getSettings() != null
  }

  CompanySettings save(CompanySettings settings) {
    validate(settings)
    databaseService.withTransaction { Sql sql ->
      GroovyRowResult row = sql.firstRow(
          'select count(*) as total from company_settings where id = ?',
          [SETTINGS_ID]
      ) as GroovyRowResult
      boolean exists = ((Number) row.get('total')).intValue() == 1
      List<Object> params = [
          SETTINGS_ID,
          settings.companyName.trim(),
          settings.organizationNumber.trim(),
          settings.defaultCurrency.trim().toUpperCase(Locale.ROOT),
          settings.localeTag.trim(),
          (settings.vatPeriodicity ?: VatPeriodicity.MONTHLY).name()
      ]
      if (exists) {
        sql.executeUpdate('''
                    update company_settings
                       set company_name = ?,
                           organization_number = ?,
                           default_currency = ?,
                           locale_tag = ?,
                           vat_periodicity = ?,
                           updated_at = current_timestamp
                     where id = ?
                ''', [params[1], params[2], params[3], params[4], params[5], params[0]])
      } else {
        sql.executeInsert('''
                    insert into company_settings (
                        id,
                        company_name,
                        organization_number,
                        default_currency,
                        locale_tag,
                        vat_periodicity,
                        created_at,
                        updated_at
                    ) values (?, ?, ?, ?, ?, ?, current_timestamp, current_timestamp)
                ''', params)
      }
      loadSettings(sql)
    }
  }

  private static CompanySettings loadSettings(Sql sql) {
    GroovyRowResult row = sql.firstRow('''
            select id,
                   company_name as companyName,
                   organization_number as organizationNumber,
                   default_currency as defaultCurrency,
                   locale_tag as localeTag,
                   vat_periodicity as vatPeriodicity
              from company_settings
             where id = ?
        ''', [SETTINGS_ID]) as GroovyRowResult
    row == null ? null : mapCompanySettings(row)
  }

  private static CompanySettings mapCompanySettings(GroovyRowResult row) {
    new CompanySettings(
        Long.valueOf(row.get('id').toString()),
        row.get('companyName') as String,
        row.get('organizationNumber') as String,
        row.get('defaultCurrency') as String,
        row.get('localeTag') as String,
        VatPeriodicity.fromDatabaseValue(row.get('vatPeriodicity') as String)
    )
  }

  private static void validate(CompanySettings settings) {
    if (settings == null) {
      throw new IllegalArgumentException('Company settings are required.')
    }
    if (!settings.companyName?.trim()) {
      throw new IllegalArgumentException('Company name is required.')
    }
    if (!settings.organizationNumber?.trim()) {
      throw new IllegalArgumentException('Organization number is required.')
    }
    if (!settings.defaultCurrency?.trim()) {
      throw new IllegalArgumentException('Default currency is required.')
    }
    if (!settings.localeTag?.trim()) {
      throw new IllegalArgumentException('Locale tag is required.')
    }
    if (settings.vatPeriodicity == null) {
      throw new IllegalArgumentException('VAT periodicity is required.')
    }
  }
}
