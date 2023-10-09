package org.techhouse.ejson.internal.bind;

import org.techhouse.ejson.EJson;
import org.techhouse.ejson.TypeAdapter;
import org.techhouse.ejson.reflect.TypeToken;
import org.techhouse.ejson.stream.JsonReader;
import org.techhouse.ejson.stream.JsonWriter;

import java.io.IOException;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;

final class TypeAdapterRuntimeTypeWrapper<T> extends TypeAdapter<T> {
  private final EJson context;
  private final TypeAdapter<T> delegate;
  private final Type type;

  TypeAdapterRuntimeTypeWrapper(EJson context, TypeAdapter<T> delegate, Type type) {
    this.context = context;
    this.delegate = delegate;
    this.type = type;
  }

  @Override
  public T read(JsonReader in) throws IOException {
    return delegate.read(in);
  }

  @Override
  public void write(JsonWriter out, T value) throws IOException {
    // Order of preference for choosing type adapters
    // First preference: a type adapter registered for the runtime type
    // Second preference: a type adapter registered for the declared type
    // Third preference: reflective type adapter for the runtime type (if it is a sub class of the declared type)
    // Fourth preference: reflective type adapter for the declared type

    TypeAdapter<T> chosen = delegate;
    Type runtimeType = getRuntimeTypeIfMoreSpecific(type, value);
    if (runtimeType != type) {
      @SuppressWarnings("unchecked")
      TypeAdapter<T> runtimeTypeAdapter = (TypeAdapter<T>) context.getAdapter(TypeToken.get(runtimeType));
      // For backward compatibility only check ReflectiveTypeAdapterFactory.Adapter here but not any other
      // wrapping adapters, see https://github.com/google/gson/pull/1787#issuecomment-1222175189
      if (!(runtimeTypeAdapter instanceof ReflectiveTypeAdapterFactory.Adapter)) {
        // The user registered a type adapter for the runtime type, so we will use that
        chosen = runtimeTypeAdapter;
      } else if (!isReflective(delegate)) {
        // The user registered a type adapter for Base class, so we prefer it over the
        // reflective type adapter for the runtime type
        chosen = delegate;
      } else {
        // Use the type adapter for runtime type
        chosen = runtimeTypeAdapter;
      }
    }
    chosen.write(out, value);
  }

  /**
   * Returns whether the type adapter uses reflection.
   *
   * @param typeAdapter the type adapter to check.
   */
  private static boolean isReflective(TypeAdapter<?> typeAdapter) {
    // Run this in loop in case multiple delegating adapters are nested
    while (typeAdapter instanceof SerializationDelegatingTypeAdapter) {
      TypeAdapter<?> delegate = ((SerializationDelegatingTypeAdapter<?>) typeAdapter).getSerializationDelegate();
      // Break if adapter does not delegate serialization
      if (delegate == typeAdapter) {
        break;
      }
      typeAdapter = delegate;
    }

    return typeAdapter instanceof ReflectiveTypeAdapterFactory.Adapter;
  }

  /**
   * Finds a compatible runtime type if it is more specific
   */
  private static Type getRuntimeTypeIfMoreSpecific(Type type, Object value) {
    if (value != null && (type instanceof Class<?> || type instanceof TypeVariable<?>)) {
      type = value.getClass();
    }
    return type;
  }
}
