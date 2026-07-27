package se.alipsa.accounting.domain

import groovy.transform.Canonical

/**
 * A candidate SRU field code for an account, as looked up from the BAS kopplingstabeller.
 * Advisory only - never auto-applied to an account's sru_code/sru_code2.
 */
@Canonical
final class SruSuggestion {

  String fieldCode
  String description
  SruSignCondition signCondition
}
