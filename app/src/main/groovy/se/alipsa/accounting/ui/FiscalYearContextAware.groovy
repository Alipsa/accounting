package se.alipsa.accounting.ui

/**
 * Implemented by long-lived tabs that should refresh their local fiscal-year
 * selector from the global fiscal-year context whenever the tab is shown.
 */
interface FiscalYearContextAware {

  void activateFiscalYearContext()
}
