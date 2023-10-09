package org.techhouse.ejson.internal.bind;

import org.techhouse.ejson.*;
import org.techhouse.ejson.internal.$EJson$Preconditions;
import org.techhouse.ejson.internal.Streams;
import org.techhouse.ejson.reflect.TypeToken;
import org.techhouse.ejson.stream.JsonReader;
import org.techhouse.ejson.stream.JsonWriter;

import java.io.IOException;
import java.lang.reflect.Type;

/**
 * Adapts a EJson 1.x tree-style adapter as a streaming TypeAdapter. Since the
 * tree adapter may be serialization-only or deserialization-only, this class
 * has a facility to lookup a delegate type adapter on demand.
 */
public final class TreeTypeAdapter<T> extends SerializationDelegatingTypeAdapter<T> {
  private final JsonSerializer<T> serializer;
  private final JsonDeserializer<T> deserializer;
  final EJson eJson;
  private final TypeToken<T> typeToken;
  /**
   * Only intended as {@code skipPast} for {@link EJson#getDelegateAdapter(TypeAdapterFactory, TypeToken)},
   * must not be used in any other way.
   */
  private final TypeAdapterFactory skipPastForGetDelegateAdapter;
  private final EJsonContextImpl context = new EJsonContextImpl();
  private final boolean nullSafe;

  /**
   * The delegate is lazily created because it may not be needed, and creating it may fail.
   * Field has to be {@code volatile} because {@link EJson} guarantees to be thread-safe.
   */
  private volatile TypeAdapter<T> delegate;

  public TreeTypeAdapter(JsonSerializer<T> serializer, JsonDeserializer<T> deserializer,
      EJson eJson, TypeToken<T> typeToken, TypeAdapterFactory skipPast, boolean nullSafe) {
    this.serializer = serializer;
    this.deserializer = deserializer;
    this.eJson = eJson;
    this.typeToken = typeToken;
    this.skipPastForGetDelegateAdapter = skipPast;
    this.nullSafe = nullSafe;
  }

  public TreeTypeAdapter(JsonSerializer<T> serializer, JsonDeserializer<T> deserializer,
                         EJson eJson, TypeToken<T> typeToken, TypeAdapterFactory skipPast) {
    this(serializer, deserializer, eJson, typeToken, skipPast, true);
  }

  @Override public T read(JsonReader in) throws IOException {
    if (deserializer == null) {
      return delegate().read(in);
    }
    JsonElement value = Streams.parse(in);
    if (nullSafe && value.isJsonNull()) {
      return null;
    }
    return deserializer.deserialize(value, typeToken.getType(), context);
  }

  @Override public void write(JsonWriter out, T value) throws IOException {
    if (serializer == null) {
      delegate().write(out, value);
      return;
    }
    if (nullSafe && value == null) {
      out.nullValue();
      return;
    }
    JsonElement tree = serializer.serialize(value, typeToken.getType(), context);
    Streams.write(tree, out);
  }

  private TypeAdapter<T> delegate() {
    // A race might lead to `delegate` being assigned by multiple threads but the last assignment will stick
    TypeAdapter<T> d = delegate;
    return d != null
        ? d
        : (delegate = eJson.getDelegateAdapter(skipPastForGetDelegateAdapter, typeToken));
  }

  /**
   * Returns the type adapter which is used for serialization. Returns {@code this}
   * if this {@code TreeTypeAdapter} has a {@link #serializer}; otherwise returns
   * the delegate.
   */
  @Override public TypeAdapter<T> getSerializationDelegate() {
    return serializer != null ? this : delegate();
  }

  /**
   * Returns a new factory that will match each type against {@code exactType}.
   */
  public static TypeAdapterFactory newFactory(TypeToken<?> exactType, Object typeAdapter) {
    return new SingleTypeFactory(typeAdapter, exactType, false, null);
  }

  /**
   * Returns a new factory that will match each type and its raw type against
   * {@code exactType}.
   */
  public static TypeAdapterFactory newFactoryWithMatchRawType(
      TypeToken<?> exactType, Object typeAdapter) {
    // only bother matching raw types if exact type is a raw type
    boolean matchRawType = exactType.getType() == exactType.getRawType();
    return new SingleTypeFactory(typeAdapter, exactType, matchRawType, null);
  }

  /**
   * Returns a new factory that will match each type's raw type for assignability
   * to {@code hierarchyType}.
   */
  public static TypeAdapterFactory newTypeHierarchyFactory(
      Class<?> hierarchyType, Object typeAdapter) {
    return new SingleTypeFactory(typeAdapter, null, false, hierarchyType);
  }

  private static final class SingleTypeFactory implements TypeAdapterFactory {
    private final TypeToken<?> exactType;
    private final boolean matchRawType;
    private final Class<?> hierarchyType;
    private final JsonSerializer<?> serializer;
    private final JsonDeserializer<?> deserializer;

    SingleTypeFactory(Object typeAdapter, TypeToken<?> exactType, boolean matchRawType,
        Class<?> hierarchyType) {
      serializer = typeAdapter instanceof JsonSerializer
          ? (JsonSerializer<?>) typeAdapter
          : null;
      deserializer = typeAdapter instanceof JsonDeserializer
          ? (JsonDeserializer<?>) typeAdapter
          : null;
      $EJson$Preconditions.checkArgument(serializer != null || deserializer != null);
      this.exactType = exactType;
      this.matchRawType = matchRawType;
      this.hierarchyType = hierarchyType;
    }

    @SuppressWarnings("unchecked") // guarded by typeToken.equals() call
    @Override
    public <T> TypeAdapter<T> create(EJson eJson, TypeToken<T> type) {
      boolean matches = exactType != null
          ? exactType.equals(type) || (matchRawType && exactType.getType() == type.getRawType())
          : hierarchyType.isAssignableFrom(type.getRawType());
      return matches
          ? new TreeTypeAdapter<>((JsonSerializer<T>) serializer,
              (JsonDeserializer<T>) deserializer, eJson, type, this)
          : null;
    }
  }

  private final class EJsonContextImpl implements JsonSerializationContext, JsonDeserializationContext {
    @Override public JsonElement serialize(Object src) {
      return eJson.toJsonTree(src);
    }
    @Override public JsonElement serialize(Object src, Type typeOfSrc) {
      return eJson.toJsonTree(src, typeOfSrc);
    }
    @Override
    @SuppressWarnings({"TypeParameterUnusedInFormals"})
    public <R> R deserialize(JsonElement json, Type typeOfT) throws JsonParseException {
      return eJson.fromJson(json, typeOfT);
    }
  }
}
