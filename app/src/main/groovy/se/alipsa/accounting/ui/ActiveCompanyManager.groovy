package se.alipsa.accounting.ui

import se.alipsa.accounting.domain.Company
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.service.CompanyService
import se.alipsa.accounting.service.FiscalYearService

import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport

/**
 * Observable holder for the active company and fiscal year. Panels listen for
 * changes to reload their data when the user switches company or fiscal year.
 */
final class ActiveCompanyManager {

  static final String COMPANY_ID_PROPERTY = 'companyId'
  static final String FISCAL_YEAR_PROPERTY = 'fiscalYear'

  private final CompanyService companyService
  private final FiscalYearService fiscalYearService
  private final PropertyChangeSupport support = new PropertyChangeSupport(this)
  private final Set<Long> openingBalanceRefreshPromptedFiscalYears = [] as Set<Long>
  private long companyId
  private FiscalYear fiscalYear

  ActiveCompanyManager(CompanyService companyService, FiscalYearService fiscalYearService) {
    this.companyService = companyService
    this.fiscalYearService = fiscalYearService
    try {
      List<Company> companies = companyService.listCompanies(true) ?: []
      this.companyId = companies.isEmpty() ? 0L : companies.first().id
      if (this.companyId > 0) {
        List<FiscalYear> years = fiscalYearService.listFiscalYears(this.companyId)
        this.fiscalYear = years.isEmpty() ? null : years.first()
      }
    } catch (Exception ignored) {
      this.companyId = 0L
    }
  }

  long getCompanyId() {
    companyId
  }

  void setCompanyId(long newCompanyId) {
    long old = this.companyId
    if (old == newCompanyId) {
      return
    }
    this.companyId = newCompanyId
    clearOpeningBalanceRefreshPrompts()
    support.firePropertyChange(COMPANY_ID_PROPERTY, old, newCompanyId)
    reloadFiscalYears()
  }

  boolean hasActiveCompany() {
    companyId > 0
  }

  Company getActiveCompany() {
    companyId > 0 ? companyService.findById(companyId) : null
  }

  FiscalYear getFiscalYear() {
    fiscalYear
  }

  void setFiscalYear(FiscalYear newFiscalYear) {
    FiscalYear old = this.fiscalYear
    this.fiscalYear = newFiscalYear
    support.firePropertyChange(FISCAL_YEAR_PROPERTY, old, newFiscalYear)
  }

  List<FiscalYear> listFiscalYears() {
    companyId > 0 ? fiscalYearService.listFiscalYears(companyId) : []
  }

  void refreshFiscalYear() {
    support.firePropertyChange(new PropertyChangeEvent(this, FISCAL_YEAR_PROPERTY, fiscalYear, fiscalYear))
  }

  void reloadFiscalYears() {
    List<FiscalYear> years = listFiscalYears()
    FiscalYear newYear = years.isEmpty() ? null : years.first()
    setFiscalYear(newYear)
  }

  boolean markOpeningBalanceRefreshPrompted(long fiscalYearId) {
    openingBalanceRefreshPromptedFiscalYears.add(fiscalYearId)
  }

  void clearOpeningBalanceRefreshPrompt(long fiscalYearId) {
    openingBalanceRefreshPromptedFiscalYears.remove(fiscalYearId)
  }

  void clearOpeningBalanceRefreshPrompts() {
    openingBalanceRefreshPromptedFiscalYears.clear()
  }

  void addPropertyChangeListener(PropertyChangeListener listener) {
    support.addPropertyChangeListener(listener)
  }

  void removePropertyChangeListener(PropertyChangeListener listener) {
    support.removePropertyChangeListener(listener)
  }
}
