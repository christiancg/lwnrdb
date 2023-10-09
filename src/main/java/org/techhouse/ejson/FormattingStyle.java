package org.techhouse.ejson;

import lombok.Getter;
import org.techhouse.ejson.stream.JsonWriter;

import java.util.Objects;

/**
 * A class used to control what the serialization output looks like.
 *
 * <p>It currently has the following configuration methods, but more methods
 * might be added in the future:
 * <ul>
 *   <li>{@link #withNewline(String)}
 *   <li>{@link #withIndent(String)}
 *   <li>{@link #withSpaceAfterSeparators(boolean)}
 * </ul>
 *
 * @see EJsonBuilder#setFormattingStyle(FormattingStyle)
 * @see JsonWriter#setFormattingStyle(FormattingStyle)
 * @see <a href="https://en.wikipedia.org/wiki/Newline">Wikipedia Newline article</a>
 *
 * @since $next-version$
 */
public class FormattingStyle {
  /**
   * -- GETTER --
   *  Returns the string value that will be used as a newline.
   */
  @Getter
  private final String newline;
  /**
   * -- GETTER --
   *  Returns the string value that will be used as indent.
   */
  @Getter
  private final String indent;
  private final boolean spaceAfterSeparators;

  /**
   * The default compact formatting style:
   * <ul>
   *   <li>no newline
   *   <li>no indent
   *   <li>no space after {@code ','} and {@code ':'}
   * </ul>
   */
  public static final FormattingStyle COMPACT = new FormattingStyle("", "", false);

  /**
   * The default pretty printing formatting style:
   * <ul>
   *   <li>{@code "\n"} as newline
   *   <li>two spaces as indent
   *   <li>a space between {@code ':'} and the subsequent value
   * </ul>
   */
  public static final FormattingStyle PRETTY =
      new FormattingStyle("\n", "  ", true);

  private FormattingStyle(String newline, String indent, boolean spaceAfterSeparators) {
    Objects.requireNonNull(newline, "newline == null");
    Objects.requireNonNull(indent, "indent == null");
    if (!newline.matches("[\r\n]*")) {
      throw new IllegalArgumentException(
          "Only combinations of \\n and \\r are allowed in newline.");
    }
    if (!indent.matches("[ \t]*")) {
      throw new IllegalArgumentException(
          "Only combinations of spaces and tabs are allowed in indent.");
    }
    this.newline = newline;
    this.indent = indent;
    this.spaceAfterSeparators = spaceAfterSeparators;
  }

  /**
   * Creates a {@link FormattingStyle} with the specified newline setting.
   *
   * <p>It can be used to accommodate certain OS convention, for example
   * hardcode {@code "\n"} for Linux and macOS, {@code "\r\n"} for Windows, or
   * call {@link System#lineSeparator()} to match the current OS.</p>
   *
   * <p>Only combinations of {@code \n} and {@code \r} are allowed.</p>
   *
   * @param newline the string value that will be used as newline.
   * @return a newly created {@link FormattingStyle}
   */
  public FormattingStyle withNewline(String newline) {
    return new FormattingStyle(newline, this.indent, this.spaceAfterSeparators);
  }

  /**
   * Creates a {@link FormattingStyle} with the specified indent string.
   *
   * <p>Only combinations of spaces and tabs allowed in indent.</p>
   *
   * @param indent the string value that will be used as indent.
   * @return a newly created {@link FormattingStyle}
   */
  public FormattingStyle withIndent(String indent) {
    return new FormattingStyle(this.newline, indent, this.spaceAfterSeparators);
  }

  /**
   * Creates a {@link FormattingStyle} which either uses a space after
   * the separators {@code ','} and {@code ':'} in the JSON output, or not.
   *
   * <p>This setting has no effect on the {@linkplain #withNewline(String) configured newline}.
   * If a non-empty newline is configured, it will always be added after
   * {@code ','} and no space is added after the {@code ','} in that case.</p>
   *
   * @param spaceAfterSeparators whether to output a space after {@code ','} and {@code ':'}.
   * @return a newly created {@link FormattingStyle}
   */
  public FormattingStyle withSpaceAfterSeparators(boolean spaceAfterSeparators) {
    return new FormattingStyle(this.newline, this.indent, spaceAfterSeparators);
  }

  /**
   * Returns whether a space will be used after {@code ','} and {@code ':'}.
   */
  public boolean usesSpaceAfterSeparators() {
    return this.spaceAfterSeparators;
  }
}
