package org.techhouse.ejson.internal;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Locale;

/**
 * Provides DateFormats for US locale with patterns which were the default ones before Java 9.
 */
public class PreJava9DateFormatProvider {

  /**
   * Returns the same DateFormat as {@code DateFormat.getDateInstance(style, Locale.US)} in Java 8 or below.
   */
  public static DateFormat getUSDateFormat(int style) {
    return new SimpleDateFormat(getDateFormatPattern(style), Locale.US);
  }

  /**
   * Returns the same DateFormat as {@code DateFormat.getDateTimeInstance(dateStyle, timeStyle, Locale.US)}
   * in Java 8 or below.
   */
  public static DateFormat getUSDateTimeFormat(int dateStyle, int timeStyle) {
    String pattern = getDatePartOfDateTimePattern(dateStyle) + " " + getTimePartOfDateTimePattern(timeStyle);
    return new SimpleDateFormat(pattern, Locale.US);
  }

  private static String getDateFormatPattern(int style) {
      return switch (style) {
          case DateFormat.SHORT -> "M/d/yy";
          case DateFormat.MEDIUM -> "MMM d, y";
          case DateFormat.LONG -> "MMMM d, y";
          case DateFormat.FULL -> "EEEE, MMMM d, y";
          default -> throw new IllegalArgumentException("Unknown DateFormat style: " + style);
      };
  }

  private static String getDatePartOfDateTimePattern(int dateStyle) {
      return switch (dateStyle) {
          case DateFormat.SHORT -> "M/d/yy";
          case DateFormat.MEDIUM -> "MMM d, yyyy";
          case DateFormat.LONG -> "MMMM d, yyyy";
          case DateFormat.FULL -> "EEEE, MMMM d, yyyy";
          default -> throw new IllegalArgumentException("Unknown DateFormat style: " + dateStyle);
      };
  }

  private static String getTimePartOfDateTimePattern(int timeStyle) {
      return switch (timeStyle) {
          case DateFormat.SHORT -> "h:mm a";
          case DateFormat.MEDIUM -> "h:mm:ss a";
          case DateFormat.FULL, DateFormat.LONG -> "h:mm:ss a z";
          default -> throw new IllegalArgumentException("Unknown DateFormat style: " + timeStyle);
      };
  }
}
