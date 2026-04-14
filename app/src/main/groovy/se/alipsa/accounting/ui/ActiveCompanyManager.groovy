package se.alipsa.accounting.ui

import se.alipsa.accounting.domain.Company
import se.alipsa.accounting.service.CompanyService

import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport

/**
 * Observable holder for the active company. Panels listen for changes
 * to reload their data when the user switches company.
 */
final class ActiveCompanyManager {

  static final String COMPANY_ID_PROPERTY = 'companyId'

  private final CompanyService companyService
  private final PropertyChangeSupport support = new PropertyChangeSupport(this)
  private long companyId

  ActiveCompanyManager(CompanyService companyService) {
    this.companyService = companyService
    List<Company> companies = companyService.listCompanies(true)
    this.companyId = companies.isEmpty() ? 0L : companies.first().id
  }

  long getCompanyId() {
    companyId
  }

  void setCompanyId(long newCompanyId) {
    long old = this.companyId
    this.companyId = newCompanyId
    support.firePropertyChange(COMPANY_ID_PROPERTY, old, newCompanyId)
  }

  boolean hasActiveCompany() {
    companyId > 0
  }

  Company getActiveCompany() {
    companyId > 0 ? companyService.findById(companyId) : null
  }

  void addPropertyChangeListener(PropertyChangeListener listener) {
    support.addPropertyChangeListener(listener)
  }

  void removePropertyChangeListener(PropertyChangeListener listener) {
    support.removePropertyChangeListener(listener)
  }
}
