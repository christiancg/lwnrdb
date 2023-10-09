package org.techhouse.ejson.stream;

import org.techhouse.ejson.Strictness;

import java.io.IOException;
import java.io.Serial;

/**
 * Thrown when a reader encounters malformed JSON. Some syntax errors can be
 * ignored by using {@link org.techhouse.ejson.Strictness#LENIENT} for {@link JsonReader#setStrictness(Strictness)}.
 */
public final class MalformedJsonException extends IOException {
  @Serial
  private static final long serialVersionUID = 1L;

  public MalformedJsonException(String msg) {
    super(msg);
  }
}
