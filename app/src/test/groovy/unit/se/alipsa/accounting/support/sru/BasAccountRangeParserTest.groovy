package se.alipsa.accounting.support.sru

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull

import org.junit.jupiter.api.Test

class BasAccountRangeParserTest {

  @Test
  void parsesPlainAccountNumber() {
    List<BasAccountRangeParser.Segment> segments = BasAccountRangeParser.parseCell('1088')
    assertEquals(1, segments.size())
    assertEquals([1088] as Set, segments[0].accounts)
    assertEquals(BasAccountRangeParser.SIGN_NONE, segments[0].signCondition)
  }

  @Test
  void parsesNumericRange() {
    List<BasAccountRangeParser.Segment> segments = BasAccountRangeParser.parseCell('1000-1087')
    assertEquals(1, segments.size())
    assertEquals((1000..1087) as Set, segments[0].accounts)
  }

  @Test
  void parsesCommaSeparatedList() {
    List<BasAccountRangeParser.Segment> segments = BasAccountRangeParser.parseCell('132x, 1340-1345, 1348-1349')
    Set<Integer> all = segments*.accounts.flatten() as Set
    assert 1320 in all
    assert 1340 in all
    assert 1345 in all
    assert 1348 in all
    assert 1349 in all
    assert !(1346 in all)
  }

  @Test
  void parsesSingleWildcard() {
    List<BasAccountRangeParser.Segment> segments = BasAccountRangeParser.parseCell('112x')
    assertEquals((1120..1129) as Set, segments[0].accounts)
  }

  @Test
  void parsesDoubleWildcard() {
    List<BasAccountRangeParser.Segment> segments = BasAccountRangeParser.parseCell('10xx')
    assertEquals((1000..1099) as Set, segments[0].accounts)
  }

  @Test
  void parsesWildcardToWildcardRangeAsContiguousSpan() {
    List<BasAccountRangeParser.Segment> segments = BasAccountRangeParser.parseCell('40xx-47xx')
    assertEquals((4000..4799) as Set, segments[0].accounts)
  }

  @Test
  void parsesExclusion() {
    List<BasAccountRangeParser.Segment> segments = BasAccountRangeParser.parseCell('10xx (exkl. 1088)')
    assert !(1088 in segments[0].accounts)
    assert 1000 in segments[0].accounts
    assert 1099 in segments[0].accounts
  }

  @Test
  void parsesRowLevelNetPositiveSuffix() {
    List<BasAccountRangeParser.Segment> segments = BasAccountRangeParser.parseCell('8000-8069, 8090-8099 (Om netto +)')
    segments.each { assertEquals(BasAccountRangeParser.SIGN_NET_POSITIVE, it.signCondition) }
  }

  @Test
  void parsesRowLevelNetNegativeSuffix() {
    List<BasAccountRangeParser.Segment> segments = BasAccountRangeParser.parseCell('8000-8069,8090-8099 (Om netto -)')
    segments.each { assertEquals(BasAccountRangeParser.SIGN_NET_NEGATIVE, it.signCondition) }
  }

  @Test
  void parsesLeadingSignPrefixAppliedToWholeCommaList() {
    List<BasAccountRangeParser.Segment> segments = BasAccountRangeParser.parseCell('+ 4900-4909, 4930-4959, 4970-4979, 4990-4999')
    segments.each { assertEquals(BasAccountRangeParser.SIGN_NET_POSITIVE, it.signCondition) }
    Set<Integer> all = segments*.accounts.flatten() as Set
    assert 4900 in all
    assert 4999 in all
  }

  @Test
  void parsesMidListSignTagWithoutTruncatingRemainder() {
    // regression test: a previous draft truncated everything after a mid-list "(Om netto +)" tag,
    // silently dropping accounts listed later in the same cell (found via real SIE file validation)
    List<BasAccountRangeParser.Segment> segments = BasAccountRangeParser.parseCell('8810 (Om netto +), 8819')
    assertEquals(2, segments.size())
    assertEquals([8810] as Set, segments[0].accounts)
    assertEquals(BasAccountRangeParser.SIGN_NET_POSITIVE, segments[0].signCondition)
    assertEquals([8819] as Set, segments[1].accounts)
    assertEquals(BasAccountRangeParser.SIGN_NONE, segments[1].signCondition)
  }

  @Test
  void returnsNullForPerTokenMixedSignLists() {
    assertNull(BasAccountRangeParser.parseCell('802x(+), 803x(+)'))
  }

  @Test
  void returnsNullForUnparseableFreeText() {
    assertNull(BasAccountRangeParser.parseCell('Summeras i blanketten'))
  }

  @Test
  void returnsEmptyListForBlankCell() {
    assertEquals([], BasAccountRangeParser.parseCell(null))
    assertEquals([], BasAccountRangeParser.parseCell('   '))
  }
}
