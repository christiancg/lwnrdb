package org.techhouse.ejson;


import java.io.Serial;

/**
 * This exception is raised when EJson attempts to read (or write) a malformed
 * JSON element.
 *
 * @author Inderjeet Singh
 * @author Joel Leitch
 */
public final class JsonSyntaxException extends JsonParseException {

  @Serial
  private static final long serialVersionUID = 1L;

  public JsonSyntaxException(String msg) {
    super(msg);
  }

  public JsonSyntaxException(String msg, Throwable cause) {
    super(msg, cause);
  }

  /**
   * Creates exception with the specified cause. Consider using
   * {@link #JsonSyntaxException(String, Throwable)} instead if you can
   * describe what actually happened.
   *
   * @param cause root exception that caused this exception to be thrown.
   */
  public JsonSyntaxException(Throwable cause) {
    super(cause);
  }
}
