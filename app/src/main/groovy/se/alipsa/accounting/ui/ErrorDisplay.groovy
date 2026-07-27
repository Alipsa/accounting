package se.alipsa.accounting.ui

import groovy.transform.PackageScope

/** Test seam for surfacing error messages to the user. */
@PackageScope
interface ErrorDisplay {
  void showError(String title, String message)
}
