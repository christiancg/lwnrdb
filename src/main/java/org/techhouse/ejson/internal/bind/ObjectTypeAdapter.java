package org.techhouse.ejson.internal.bind;

import org.techhouse.ejson.*;
import org.techhouse.ejson.internal.LinkedTreeMap;
import org.techhouse.ejson.reflect.TypeToken;
import org.techhouse.ejson.stream.JsonReader;
import org.techhouse.ejson.stream.JsonToken;
import org.techhouse.ejson.stream.JsonWriter;

import java.io.IOException;
import java.util.*;

/**
 * Adapts types whose static type is only 'Object'. Uses getClass() on
 * serialization and a primitive/Map/List on deserialization.
 */
public final class ObjectTypeAdapter extends TypeAdapter<Object> {
  /**
   * EJson default factory using {@link ToNumberPolicy#DOUBLE}.
   */
  private static final TypeAdapterFactory DOUBLE_FACTORY = newFactory(ToNumberPolicy.DOUBLE);

  private final EJson eJson;
  private final ToNumberStrategy toNumberStrategy;

  private ObjectTypeAdapter(EJson eJson, ToNumberStrategy toNumberStrategy) {
    this.eJson = eJson;
    this.toNumberStrategy = toNumberStrategy;
  }

  private static TypeAdapterFactory newFactory(final ToNumberStrategy toNumberStrategy) {
    return new TypeAdapterFactory() {
      @SuppressWarnings("unchecked")
      @Override public <T> TypeAdapter<T> create(EJson eJson, TypeToken<T> type) {
        if (type.getRawType() == Object.class) {
          return (TypeAdapter<T>) new ObjectTypeAdapter(eJson, toNumberStrategy);
        }
        return null;
      }
    };
  }

  public static TypeAdapterFactory getFactory(ToNumberStrategy toNumberStrategy) {
    if (toNumberStrategy == ToNumberPolicy.DOUBLE) {
      return DOUBLE_FACTORY;
    } else {
      return newFactory(toNumberStrategy);
    }
  }

  /**
   * Tries to begin reading a JSON array or JSON object, returning {@code null} if
   * the next element is neither of those.
   */
  private Object tryBeginNesting(JsonReader in, JsonToken peeked) throws IOException {
      return switch (peeked) {
          case BEGIN_ARRAY -> {
              in.beginArray();
              yield new ArrayList<>();
          }
          case BEGIN_OBJECT -> {
              in.beginObject();
              yield new LinkedTreeMap<>();
          }
          default -> null;
      };
  }

  /** Reads an {@code Object} which cannot have any nested elements */
  private Object readTerminal(JsonReader in, JsonToken peeked) throws IOException {
      // When read(JsonReader) is called with JsonReader in invalid state
      return switch (peeked) {
          case STRING -> in.nextString();
          case NUMBER -> toNumberStrategy.readNumber(in);
          case BOOLEAN -> in.nextBoolean();
          case NULL -> {
              in.nextNull();
              yield null;
          }
          default -> throw new IllegalStateException("Unexpected token: " + peeked);
      };
  }

  @Override public Object read(JsonReader in) throws IOException {
    // Either List or Map
    Object current;
    JsonToken peeked = in.peek();

    current = tryBeginNesting(in, peeked);
    if (current == null) {
      return readTerminal(in, peeked);
    }

    Deque<Object> stack = new ArrayDeque<>();

    while (true) {
      while (in.hasNext()) {
        String name = null;
        // Name is only used for JSON object members
        if (current instanceof Map) {
          name = in.nextName();
        }

        peeked = in.peek();
        Object value = tryBeginNesting(in, peeked);
        boolean isNesting = value != null;

        if (value == null) {
          value = readTerminal(in, peeked);
        }

        if (current instanceof List) {
          @SuppressWarnings("unchecked")
          List<Object> list = (List<Object>) current;
          list.add(value);
        } else {
          @SuppressWarnings("unchecked")
          Map<String, Object> map = (Map<String, Object>) current;
          map.put(name, value);
        }

        if (isNesting) {
          stack.addLast(current);
          current = value;
        }
      }

      // End current element
      if (current instanceof List) {
        in.endArray();
      } else {
        in.endObject();
      }

      if (stack.isEmpty()) {
        return current;
      } else {
        // Continue with enclosing element
        current = stack.removeLast();
      }
    }
  }

  @Override public void write(JsonWriter out, Object value) throws IOException {
    if (value == null) {
      out.nullValue();
      return;
    }

    @SuppressWarnings("unchecked")
    TypeAdapter<Object> typeAdapter = (TypeAdapter<Object>) eJson.getAdapter(value.getClass());
    if (typeAdapter instanceof ObjectTypeAdapter) {
      out.beginObject();
      out.endObject();
      return;
    }

    typeAdapter.write(out, value);
  }
}
