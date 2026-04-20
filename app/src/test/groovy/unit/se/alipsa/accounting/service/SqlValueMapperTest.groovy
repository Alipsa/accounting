package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows

import org.junit.jupiter.api.Test

import java.sql.Clob

class SqlValueMapperTest {

  @Test
  void toClobReturnsNullForNull() {
    assertNull(SqlValueMapper.toClob(null))
  }

  @Test
  void toClobReturnsStringAsIs() {
    assertEquals('hello', SqlValueMapper.toClob('hello'))
  }

  @Test
  void toClobReadsClob() {
    Clob clob = new StubClob('clob content')
    assertEquals('clob content', SqlValueMapper.toClob(clob))
  }

  @Test
  void toClobThrowsForUnsupportedType() {
    assertThrows(IllegalStateException) {
      SqlValueMapper.toClob(42)
    }
  }

  private static class StubClob implements Clob {

    private final String text

    StubClob(String text) {
      this.text = text
    }

    @Override
    long length() { text.length() }

    @Override
    String getSubString(long pos, int length) { text.substring((int) (pos - 1), (int) (pos - 1) + length) }

    @Override
    Reader getCharacterStream() { new StringReader(text) }

    @Override
    InputStream getAsciiStream() { throw new UnsupportedOperationException() }

    @Override
    long position(String searchstr, long start) { throw new UnsupportedOperationException() }

    @Override
    long position(Clob searchstr, long start) { throw new UnsupportedOperationException() }

    @Override
    int setString(long pos, String str) { throw new UnsupportedOperationException() }

    @Override
    int setString(long pos, String str, int offset, int len) { throw new UnsupportedOperationException() }

    @Override
    OutputStream setAsciiStream(long pos) { throw new UnsupportedOperationException() }

    @Override
    Writer setCharacterStream(long pos) { throw new UnsupportedOperationException() }

    @Override
    void truncate(long len) { throw new UnsupportedOperationException() }

    @Override
    void free() {}

    @Override
    Reader getCharacterStream(long pos, long length) { throw new UnsupportedOperationException() }
  }
}
