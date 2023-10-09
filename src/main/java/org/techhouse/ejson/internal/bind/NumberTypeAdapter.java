package org.techhouse.ejson.internal.bind;

import org.techhouse.ejson.*;
import org.techhouse.ejson.reflect.TypeToken;
import org.techhouse.ejson.stream.JsonReader;
import org.techhouse.ejson.stream.JsonToken;
import org.techhouse.ejson.stream.JsonWriter;

import java.io.IOException;

/**
 * Type adapter for {@link Number}.
 */
public final class NumberTypeAdapter extends TypeAdapter<Number> {
  /**
   * EJson default factory using {@link ToNumberPolicy#LAZILY_PARSED_NUMBER}.
   */
  private static final TypeAdapterFactory LAZILY_PARSED_NUMBER_FACTORY = newFactory(ToNumberPolicy.LAZILY_PARSED_NUMBER);

  private final ToNumberStrategy toNumberStrategy;

  private NumberTypeAdapter(ToNumberStrategy toNumberStrategy) {
    this.toNumberStrategy = toNumberStrategy;
  }

  private static TypeAdapterFactory newFactory(ToNumberStrategy toNumberStrategy) {
    final NumberTypeAdapter adapter = new NumberTypeAdapter(toNumberStrategy);
    return new TypeAdapterFactory() {
      @SuppressWarnings("unchecked")
      @Override public <T> TypeAdapter<T> create(EJson eJson, TypeToken<T> type) {
        return type.getRawType() == Number.class ? (TypeAdapter<T>) adapter : null;
      }
    };
  }

  public static TypeAdapterFactory getFactory(ToNumberStrategy toNumberStrategy) {
    if (toNumberStrategy == ToNumberPolicy.LAZILY_PARSED_NUMBER) {
      return LAZILY_PARSED_NUMBER_FACTORY;
    } else {
      return newFactory(toNumberStrategy);
    }
  }

  @Override public Number read(JsonReader in) throws IOException {
    JsonToken jsonToken = in.peek();
      return switch (jsonToken) {
          case NULL -> {
              in.nextNull();
              yield null;
          }
          case NUMBER, STRING -> toNumberStrategy.readNumber(in);
          default -> throw new JsonSyntaxException("Expecting number, got: " + jsonToken + "; at path " + in.getPath());
      };
  }

  @Override public void write(JsonWriter out, Number value) throws IOException {
    out.value(value);
  }
}
