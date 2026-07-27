package se.alipsa.accounting.support.sru

import java.util.regex.Matcher

/**
 * Parses the "Konton i BAS ..." account-range column from the official BAS SRU kopplingstabeller
 * (bas.se/kontoplaner/sru/). No I/O - see BasSruTableConverter for reading spreadsheets.
 */
final class BasAccountRangeParser {

  static final String SIGN_NONE = 'NONE'
  static final String SIGN_NET_POSITIVE = 'NET_POSITIVE'
  static final String SIGN_NET_NEGATIVE = 'NET_NEGATIVE'

  private BasAccountRangeParser() {
  }

  static final class Segment {

    final Set<Integer> accounts
    final String signCondition

    Segment(Set<Integer> accounts, String signCondition) {
      this.accounts = accounts
      this.signCondition = signCondition
    }
  }

  /**
   * Parses one cell into comma-segments, or null if any segment can't be confidently classified
   * (mixed per-token sign markers, free-text notes) - callers must treat a null result as "exclude
   * this whole row", not attempt to salvage the parseable segments within it.
   */
  @SuppressWarnings('ReturnsNullInsteadOfEmptyCollection')
  static List<Segment> parseCell(String rawValue) {
    if (!rawValue?.trim()) {
      return []
    }
    String value = rawValue.trim().replace('–', '-').replace('−', '-')

    String rowSign = null
    Matcher rowPrefix = value =~ /^([+-])\s*(.*)/
    if (rowPrefix.matches()) {
      rowSign = rowPrefix.group(1) == '+' ? SIGN_NET_POSITIVE : SIGN_NET_NEGATIVE
      value = rowPrefix.group(2).trim()
    }

    List<Segment> segments = []
    List<Boolean> explicitTag = []
    for (String rawPart : value.split(',')) {
      String part = rawPart.trim()
      if (!part) {
        continue
      }
      String sign = rowSign ?: SIGN_NONE
      boolean hasExplicitTag = false
      Matcher signSuffix = part =~ /\(Om netto\s*([+-])\)/
      if (signSuffix.find()) {
        sign = signSuffix.group(1) == '+' ? SIGN_NET_POSITIVE : SIGN_NET_NEGATIVE
        hasExplicitTag = true
        part = part.substring(0, signSuffix.start()).trim()
      }
      if (part ==~ /.*\(\s*[+-]\s*\).*/) {
        return null
      }
      part = part.replaceAll(/\s+/, '')
      Set<Integer> excluded = [] as Set
      Matcher exclusion = part =~ /^(.*)\(exkl\.(.+)\)$/
      if (exclusion.matches()) {
        part = exclusion.group(1)
        (exclusion.group(2) =~ /\d+/).each { excluded << (it as Integer) }
      }
      Set<Integer> expanded = expandToken(part)
      if (expanded == null) {
        return null
      }
      expanded.removeAll(excluded)
      segments << new Segment(expanded, sign)
      explicitTag << hasExplicitTag
    }
    // A "(Om netto +/-)" tag on the LAST comma-segment describes the whole preceding list (the
    // common convention of tagging a multi-range row once, at the end) and is propagated back to
    // every segment - but a tag on an earlier segment is local to that segment only (see
    // parsesMidListSignTagWithoutTruncatingRemainder).
    if (explicitTag && explicitTag.last()) {
      String rowLevelSign = segments.last().signCondition
      segments = segments.collect { Segment segment -> new Segment(segment.accounts, rowLevelSign) }
    }
    segments
  }

  private static Set<Integer> expandToken(String token) {
    if (token ==~ /\d*x+-\d*x+/) {
      String[] parts = token.split('-')
      int lo = wildcardBounds(parts[0])[0]
      int hi = wildcardBounds(parts[1])[1]
      return (lo..hi) as Set<Integer>
    }
    if (token ==~ /\d+-\d+/) {
      String[] parts = token.split('-')
      return (parts[0].toInteger()..parts[1].toInteger()) as Set<Integer>
    }
    if (token ==~ /\d*x+/) {
      int[] bounds = wildcardBounds(token)
      return (bounds[0]..bounds[1]) as Set<Integer>
    }
    if (token ==~ /\d{3,4}/) {
      return [token.toInteger()] as Set<Integer>
    }
    null
  }

  private static int[] wildcardBounds(String token) {
    Matcher matcher = token =~ /^(\d*)(x+)$/
    if (!matcher.matches()) {
      throw new IllegalArgumentException("Not a wildcard token: ${token}")
    }
    String digits = matcher.group(1)
    int xCount = matcher.group(2).length()
    String zeros = '0' * xCount
    String nines = '9' * xCount
    [(digits + zeros).toInteger(), (digits + nines).toInteger()] as int[]
  }
}
