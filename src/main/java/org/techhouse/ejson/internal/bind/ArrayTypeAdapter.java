package org.techhouse.ejson.internal.bind;

import org.techhouse.ejson.EJson;
import org.techhouse.ejson.TypeAdapter;
import org.techhouse.ejson.TypeAdapterFactory;
import org.techhouse.ejson.internal.$EJson$Types;
import org.techhouse.ejson.reflect.TypeToken;
import org.techhouse.ejson.stream.JsonReader;
import org.techhouse.ejson.stream.JsonToken;
import org.techhouse.ejson.stream.JsonWriter;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Type;
import java.util.ArrayList;

/**
 * Adapt an array of objects.
 */
public final class ArrayTypeAdapter<E> extends TypeAdapter<Object> {
  public static final TypeAdapterFactory FACTORY = new TypeAdapterFactory() {
    @Override public <T> TypeAdapter<T> create(EJson eJson, TypeToken<T> typeToken) {
      Type type = typeToken.getType();
      if (!(type instanceof GenericArrayType || (type instanceof Class && ((Class<?>) type).isArray()))) {
        return null;
      }

      Type componentType = $EJson$Types.getArrayComponentType(type);
      TypeAdapter<?> componentTypeAdapter = eJson.getAdapter(TypeToken.get(componentType));

      @SuppressWarnings({"unchecked", "rawtypes"})
      TypeAdapter<T> arrayAdapter = new ArrayTypeAdapter(
              eJson, componentTypeAdapter, $EJson$Types.getRawType(componentType));
      return arrayAdapter;
    }
  };

  private final Class<E> componentType;
  private final TypeAdapter<E> componentTypeAdapter;

  public ArrayTypeAdapter(EJson context, TypeAdapter<E> componentTypeAdapter, Class<E> componentType) {
    this.componentTypeAdapter =
      new TypeAdapterRuntimeTypeWrapper<>(context, componentTypeAdapter, componentType);
    this.componentType = componentType;
  }

  @Override public Object read(JsonReader in) throws IOException {
    if (in.peek() == JsonToken.NULL) {
      in.nextNull();
      return null;
    }

    ArrayList<E> list = new ArrayList<>();
    in.beginArray();
    while (in.hasNext()) {
      E instance = componentTypeAdapter.read(in);
      list.add(instance);
    }
    in.endArray();

    int size = list.size();
    // Have to copy primitives one by one to primitive array
    if (componentType.isPrimitive()) {
      Object array = Array.newInstance(componentType, size);
      for (int i = 0; i < size; i++) {
        Array.set(array, i, list.get(i));
      }
      return array;
    }
    // But for Object[] can use ArrayList.toArray
    else {
      @SuppressWarnings("unchecked")
      E[] array = (E[]) Array.newInstance(componentType, size);
      return list.toArray(array);
    }
  }

  @Override public void write(JsonWriter out, Object array) throws IOException {
    if (array == null) {
      out.nullValue();
      return;
    }

    out.beginArray();
    for (int i = 0, length = Array.getLength(array); i < length; i++) {
      @SuppressWarnings("unchecked")
      E value = (E) Array.get(array, i);
      componentTypeAdapter.write(out, value);
    }
    out.endArray();
  }
}
