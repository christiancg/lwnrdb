package org.techhouse.ejson.internal.bind;

import org.techhouse.ejson.JsonSyntaxException;
import org.techhouse.ejson.TypeAdapter;
import org.techhouse.ejson.TypeAdapterFactory;
import org.techhouse.ejson.internal.PreJava9DateFormatProvider;
import org.techhouse.ejson.internal.bind.util.ISO8601Utils;
import org.techhouse.ejson.stream.JsonReader;
import org.techhouse.ejson.stream.JsonToken;
import org.techhouse.ejson.stream.JsonWriter;

import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * This type adapter supports subclasses of date by defining a
 * {@link DateType} and then using its {@code createAdapterFactory}
 * methods.
 *
 * @author Inderjeet Singh
 * @author Joel Leitch
 */
public final class DefaultDateTypeAdapter<T extends Date> extends TypeAdapter<T> {
  private static final String SIMPLE_NAME = "DefaultDateTypeAdapter";

  public static abstract class DateType<T extends Date> {
    public static final DateType<Date> DATE = new DateType<>(Date.class) {
        @Override
        protected Date deserialize(Date date) {
            return date;
        }
    };

    private final Class<T> dateClass;

    protected DateType(Class<T> dateClass) {
      this.dateClass = dateClass;
    }

    protected abstract T deserialize(Date date);

    private TypeAdapterFactory createFactory(DefaultDateTypeAdapter<T> adapter) {
      return TypeAdapters.newFactory(dateClass, adapter);
    }

    public final TypeAdapterFactory createAdapterFactory(String datePattern) {
      return createFactory(new DefaultDateTypeAdapter<>(this, datePattern));
    }

    public final TypeAdapterFactory createAdapterFactory(int style) {
      return createFactory(new DefaultDateTypeAdapter<>(this, style));
    }

    public final TypeAdapterFactory createAdapterFactory(int dateStyle, int timeStyle) {
      return createFactory(new DefaultDateTypeAdapter<>(this, dateStyle, timeStyle));
    }

    public final TypeAdapterFactory createDefaultsAdapterFactory() {
      return createFactory(new DefaultDateTypeAdapter<>(this, DateFormat.DEFAULT, DateFormat.DEFAULT));
    }
  }

  private final DateType<T> dateType;

  /**
   * List of 1 or more different date formats used for de-serialization attempts.
   * The first of them is used for serialization as well.
   */
  private final List<DateFormat> dateFormats = new ArrayList<>();

  private DefaultDateTypeAdapter(DateType<T> dateType, String datePattern) {
    this.dateType = Objects.requireNonNull(dateType);
    dateFormats.add(new SimpleDateFormat(datePattern, Locale.US));
    if (!Locale.getDefault().equals(Locale.US)) {
      dateFormats.add(new SimpleDateFormat(datePattern));
    }
  }

  private DefaultDateTypeAdapter(DateType<T> dateType, int style) {
    this.dateType = Objects.requireNonNull(dateType);
    dateFormats.add(DateFormat.getDateInstance(style, Locale.US));
    if (!Locale.getDefault().equals(Locale.US)) {
      dateFormats.add(DateFormat.getDateInstance(style));
    }
    dateFormats.add(PreJava9DateFormatProvider.getUSDateFormat(style));
  }

  private DefaultDateTypeAdapter(DateType<T> dateType, int dateStyle, int timeStyle) {
    this.dateType = Objects.requireNonNull(dateType);
    dateFormats.add(DateFormat.getDateTimeInstance(dateStyle, timeStyle, Locale.US));
    if (!Locale.getDefault().equals(Locale.US)) {
      dateFormats.add(DateFormat.getDateTimeInstance(dateStyle, timeStyle));
    }
    dateFormats.add(PreJava9DateFormatProvider.getUSDateTimeFormat(dateStyle, timeStyle));
  }

  // These methods need to be synchronized since JDK DateFormat classes are not thread-safe
  // See issue 162
  @Override
  public void write(JsonWriter out, Date value) throws IOException {
    if (value == null) {
      out.nullValue();
      return;
    }

    DateFormat dateFormat = dateFormats.get(0);
    String dateFormatAsString;
    synchronized (dateFormats) {
      dateFormatAsString = dateFormat.format(value);
    }
    out.value(dateFormatAsString);
  }

  @Override
  public T read(JsonReader in) throws IOException {
    if (in.peek() == JsonToken.NULL) {
      in.nextNull();
      return null;
    }
    Date date = deserializeToDate(in);
    return dateType.deserialize(date);
  }

  private Date deserializeToDate(JsonReader in) throws IOException {
    String s = in.nextString();
    synchronized (dateFormats) {
      for (DateFormat dateFormat : dateFormats) {
        try {
          return dateFormat.parse(s);
        } catch (ParseException ignored) {
          // OK: try the next format
        }
      }
    }

    try {
      return ISO8601Utils.parse(s, new ParsePosition(0));
    } catch (ParseException e) {
      throw new JsonSyntaxException("Failed parsing '" + s + "' as Date; at path " + in.getPreviousPath(), e);
    }
  }

  @Override
  public String toString() {
    DateFormat defaultFormat = dateFormats.get(0);
    if (defaultFormat instanceof SimpleDateFormat) {
      return SIMPLE_NAME + '(' + ((SimpleDateFormat) defaultFormat).toPattern() + ')';
    } else {
      return SIMPLE_NAME + '(' + defaultFormat.getClass().getSimpleName() + ')';
    }
  }
}
