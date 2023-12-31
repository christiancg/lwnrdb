package org.techhouse.ejson.internal.bind;

import org.techhouse.ejson.*;
import org.techhouse.ejson.internal.*;
import org.techhouse.ejson.internal.reflect.ReflectionHelper;
import org.techhouse.ejson.reflect.TypeToken;
import org.techhouse.ejson.stream.JsonReader;
import org.techhouse.ejson.stream.JsonToken;
import org.techhouse.ejson.stream.JsonWriter;

import java.io.IOException;
import java.lang.reflect.*;
import java.util.*;

/**
 * Type adapter that reflects over the fields and methods of a class.
 */
public final class ReflectiveTypeAdapterFactory implements TypeAdapterFactory {
  private final ConstructorConstructor constructorConstructor;
  private final FieldNamingStrategy fieldNamingPolicy;
  private final Excluder excluder;
  private final List<ReflectionAccessFilter> reflectionFilters;

  public ReflectiveTypeAdapterFactory(ConstructorConstructor constructorConstructor,
      FieldNamingStrategy fieldNamingPolicy, Excluder excluder,
      List<ReflectionAccessFilter> reflectionFilters) {
    this.constructorConstructor = constructorConstructor;
    this.fieldNamingPolicy = fieldNamingPolicy;
    this.excluder = excluder;
    this.reflectionFilters = reflectionFilters;
  }

  private boolean includeField(Field f, boolean serialize) {
    return !excluder.excludeClass(f.getType(), serialize) && !excluder.excludeField(f, serialize);
  }

  /** first element holds the default name */
  @SuppressWarnings("MixedMutabilityReturnType")
  private List<String> getFieldNames(Field f) {
    String name = fieldNamingPolicy.translateName(f);
    return Collections.singletonList(name);
  }

  @Override
  public <T> TypeAdapter<T> create(EJson eJson, final TypeToken<T> type) {
    Class<? super T> raw = type.getRawType();

    if (!Object.class.isAssignableFrom(raw)) {
      return null; // it's a primitive!
    }

    ReflectionAccessFilter.FilterResult filterResult =
        ReflectionAccessFilterHelper.getFilterResult(reflectionFilters, raw);
    if (filterResult == ReflectionAccessFilter.FilterResult.BLOCK_ALL) {
      throw new JsonIOException(
          "ReflectionAccessFilter does not permit using reflection for " + raw
          + ". Register a TypeAdapter for this type or adjust the access filter.");
    }
    boolean blockInaccessible = filterResult == ReflectionAccessFilter.FilterResult.BLOCK_INACCESSIBLE;

    // If the type is actually a Java Record, we need to use the RecordAdapter instead. This will always be false
    // on JVMs that do not support records.
    if (ReflectionHelper.isRecord(raw)) {
      @SuppressWarnings("unchecked")
      TypeAdapter<T> adapter = (TypeAdapter<T>) new RecordAdapter<>(raw,
          getBoundFields(eJson, type, raw, blockInaccessible, true), blockInaccessible);
      return adapter;
    }

    ObjectConstructor<T> constructor = constructorConstructor.get(type);
    return new FieldReflectionAdapter<>(constructor, getBoundFields(eJson, type, raw, blockInaccessible, false));
  }

  private static <M extends AccessibleObject & Member> void checkAccessible(Object object, M member) {
    if (!ReflectionAccessFilterHelper.canAccess(member, Modifier.isStatic(member.getModifiers()) ? null : object)) {
      String memberDescription = ReflectionHelper.getAccessibleObjectDescription(member, true);
      throw new JsonIOException(memberDescription + " is not accessible and ReflectionAccessFilter does not"
          + " permit making it accessible. Register a TypeAdapter for the declaring type, adjust the"
          + " access filter or increase the visibility of the element and its declaring type.");
    }
  }

  private BoundField createBoundField(
      final EJson context, final Field field, final Method accessor, final String name,
      final TypeToken<?> fieldType, boolean serialize, boolean deserialize,
      final boolean blockInaccessible) {

    final boolean isPrimitive = Primitives.isPrimitive(fieldType.getRawType());

    int modifiers = field.getModifiers();
    final boolean isStaticFinalField = Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers);

    TypeAdapter<?> mapped = context.getAdapter(fieldType);

    @SuppressWarnings("unchecked")
    final TypeAdapter<Object> typeAdapter = (TypeAdapter<Object>) mapped;
    final TypeAdapter<Object> writeTypeAdapter;
    if (serialize) {
      writeTypeAdapter = new TypeAdapterRuntimeTypeWrapper<>(context, typeAdapter, fieldType.getType());
    } else {
      // Will never actually be used, but we set it to avoid confusing nullness-analysis tools
      writeTypeAdapter = typeAdapter;
    }
    return new BoundField(name, field, serialize, deserialize) {
      @Override void write(JsonWriter writer, Object source)
          throws IOException, IllegalAccessException {
        if (!serialized) return;
        if (blockInaccessible) {
            // Note: This check might actually be redundant because access check for canonical
            // constructor should have failed already
            checkAccessible(source, Objects.requireNonNullElse(accessor, field));
        }

        Object fieldValue;
        if (accessor != null) {
          try {
            fieldValue = accessor.invoke(source);
          } catch (InvocationTargetException e) {
            String accessorDescription = ReflectionHelper.getAccessibleObjectDescription(accessor, false);
            throw new JsonIOException("Accessor " + accessorDescription + " threw exception", e.getCause());
          }
        } else {
          fieldValue = field.get(source);
        }
        if (fieldValue == source) {
          // avoid direct recursion
          return;
        }
        writer.name(name);
        writeTypeAdapter.write(writer, fieldValue);
      }

      @Override
      void readIntoArray(JsonReader reader, int index, Object[] target) throws IOException, JsonParseException {
        Object fieldValue = typeAdapter.read(reader);
        if (fieldValue == null && isPrimitive) {
          throw new JsonParseException("null is not allowed as value for record component '" + fieldName + "'"
              + " of primitive type; at path " + reader.getPath());
        }
        target[index] = fieldValue;
      }

      @Override
      void readIntoField(JsonReader reader, Object target)
          throws IOException, IllegalAccessException {
        Object fieldValue = typeAdapter.read(reader);
        if (fieldValue != null || !isPrimitive) {
          if (blockInaccessible) {
            checkAccessible(target, field);
          } else if (isStaticFinalField) {
            // Reflection does not permit setting value of `static final` field, even after calling `setAccessible`
            // Handle this here to avoid causing IllegalAccessException when calling `Field.set`
            String fieldDescription = ReflectionHelper.getAccessibleObjectDescription(field, false);
            throw new JsonIOException("Cannot set value of 'static final' " + fieldDescription);
          }
          field.set(target, fieldValue);
        }
      }
    };
  }

  private Map<String, BoundField> getBoundFields(EJson context, TypeToken<?> type, Class<?> raw,
                                                 boolean blockInaccessible, boolean isRecord) {
    Map<String, BoundField> result = new LinkedHashMap<>();
    if (raw.isInterface()) {
      return result;
    }

    Class<?> originalRaw = raw;
    while (raw != Object.class) {
      Field[] fields = raw.getDeclaredFields();

      // For inherited fields, check if access to their declaring class is allowed
      if (raw != originalRaw && fields.length > 0) {
        ReflectionAccessFilter.FilterResult filterResult = ReflectionAccessFilterHelper.getFilterResult(reflectionFilters, raw);
        if (filterResult == ReflectionAccessFilter.FilterResult.BLOCK_ALL) {
          throw new JsonIOException("ReflectionAccessFilter does not permit using reflection for " + raw
              + " (supertype of " + originalRaw + "). Register a TypeAdapter for this type"
              + " or adjust the access filter.");
        }
        blockInaccessible = filterResult == ReflectionAccessFilter.FilterResult.BLOCK_INACCESSIBLE;
      }

      for (Field field : fields) {
        boolean serialize = includeField(field, true);
        boolean deserialize = includeField(field, false);
        if (!serialize && !deserialize) {
          continue;
        }
        // The accessor method is only used for records. If the type is a record, we will read out values
        // via its accessor method instead of via reflection. This way we will bypass the accessible restrictions
        Method accessor = null;
        if (isRecord) {
          // If there is a static field on a record, there will not be an accessor. Instead we will use the default
          // field serialization logic, but for deserialization the field is excluded for simplicity. Note that EJson
          // ignores static fields by default, but EJsonBuilder.excludeFieldsWithModifiers can overwrite this.
          if (Modifier.isStatic(field.getModifiers())) {
            deserialize = false;
          } else {
            accessor = ReflectionHelper.getAccessor(raw, field);
            // If blockInaccessible, skip and perform access check later
            if (!blockInaccessible) {
              ReflectionHelper.makeAccessible(accessor);
            }
          }
        }

        // If blockInaccessible, skip and perform access check later
        // For Records if the accessor method is used the field does not have to be made accessible
        if (!blockInaccessible && accessor == null) {
          ReflectionHelper.makeAccessible(field);
        }
        Type fieldType = $EJson$Types.resolve(type.getType(), raw, field.getGenericType());
        List<String> fieldNames = getFieldNames(field);
        BoundField previous = null;
        for (int i = 0, size = fieldNames.size(); i < size; ++i) {
          String name = fieldNames.get(i);
          if (i != 0) serialize = false; // only serialize the default name
          BoundField boundField = createBoundField(context, field, accessor, name,
              TypeToken.get(fieldType), serialize, deserialize, blockInaccessible);
          BoundField replaced = result.put(name, boundField);
          if (previous == null) previous = replaced;
        }
        if (previous != null) {
          throw new IllegalArgumentException("Class " + originalRaw.getName()
              + " declares multiple JSON fields named '" + previous.name + "'; conflict is caused"
              + " by fields " + ReflectionHelper.fieldToString(previous.field) + " and " + ReflectionHelper.fieldToString(field)
              + "\nSee " + TroubleshootingGuide.createUrl("duplicate-fields"));
        }
      }
      type = TypeToken.get($EJson$Types.resolve(type.getType(), raw, raw.getGenericSuperclass()));
      raw = type.getRawType();
    }
    return result;
  }

  static abstract class BoundField {
    final String name;
    final Field field;
    /** Name of the underlying field */
    final String fieldName;
    final boolean serialized;
    final boolean deserialized;

    protected BoundField(String name, Field field, boolean serialized, boolean deserialized) {
      this.name = name;
      this.field = field;
      this.fieldName = field.getName();
      this.serialized = serialized;
      this.deserialized = deserialized;
    }

    /** Read this field value from the source, and append its JSON value to the writer */
    abstract void write(JsonWriter writer, Object source) throws IOException, IllegalAccessException;

    /** Read the value into the target array, used to provide constructor arguments for records */
    abstract void readIntoArray(JsonReader reader, int index, Object[] target) throws IOException, JsonParseException;

    /** Read the value from the reader, and set it on the corresponding field on target via reflection */
    abstract void readIntoField(JsonReader reader, Object target) throws IOException, IllegalAccessException;
  }

  /**
   * Base class for Adapters produced by this factory.
   *
   * <p>The {@link RecordAdapter} is a special case to handle records for JVMs that support it, for
   * all other types we use the {@link FieldReflectionAdapter}. This class encapsulates the common
   * logic for serialization and deserialization. During deserialization, we construct an
   * accumulator A, which we use to accumulate values from the source JSON. After the object has been read in
   * full, the {@link #finalize(Object)} method is used to convert the accumulator to an instance
   * of T.
   *
   * @param <T> type of objects that this Adapter creates.
   * @param <A> type of accumulator used to build the deserialization result.
   */
  // This class is public because external projects check for this class with `instanceof` (even though it is internal)
  public static abstract class Adapter<T, A> extends TypeAdapter<T> {
    final Map<String, BoundField> boundFields;

    Adapter(Map<String, BoundField> boundFields) {
      this.boundFields = boundFields;
    }

    @Override
    public void write(JsonWriter out, T value) throws IOException {
      if (value == null) {
        out.nullValue();
        return;
      }

      out.beginObject();
      try {
        for (BoundField boundField : boundFields.values()) {
          boundField.write(out, value);
        }
      } catch (IllegalAccessException e) {
        throw ReflectionHelper.createExceptionForUnexpectedIllegalAccess(e);
      }
      out.endObject();
    }

    @Override
    public T read(JsonReader in) throws IOException {
      if (in.peek() == JsonToken.NULL) {
        in.nextNull();
        return null;
      }

      A accumulator = createAccumulator();

      try {
        in.beginObject();
        while (in.hasNext()) {
          String name = in.nextName();
          BoundField field = boundFields.get(name);
          if (field == null || !field.deserialized) {
            in.skipValue();
          } else {
            readField(accumulator, in, field);
          }
        }
      } catch (IllegalStateException e) {
        throw new JsonSyntaxException(e);
      } catch (IllegalAccessException e) {
        throw ReflectionHelper.createExceptionForUnexpectedIllegalAccess(e);
      }
      in.endObject();
      return finalize(accumulator);
    }

    /** Create the Object that will be used to collect each field value */
    abstract A createAccumulator();
    /**
     * Read a single BoundField into the accumulator. The JsonReader will be pointed at the
     * start of the value for the BoundField to read from.
     */
    abstract void readField(A accumulator, JsonReader in, BoundField field)
        throws IllegalAccessException, IOException;
    /** Convert the accumulator to a final instance of T. */
    abstract T finalize(A accumulator);
  }

  private static final class FieldReflectionAdapter<T> extends Adapter<T, T> {
    private final ObjectConstructor<T> constructor;

    FieldReflectionAdapter(ObjectConstructor<T> constructor, Map<String, BoundField> boundFields) {
      super(boundFields);
      this.constructor = constructor;
    }

    @Override
    T createAccumulator() {
      return constructor.construct();
    }

    @Override
    void readField(T accumulator, JsonReader in, BoundField field)
        throws IllegalAccessException, IOException {
      field.readIntoField(in, accumulator);
    }

    @Override
    T finalize(T accumulator) {
      return accumulator;
    }
  }

  private static final class RecordAdapter<T> extends Adapter<T, Object[]> {
    static final Map<Class<?>, Object> PRIMITIVE_DEFAULTS = primitiveDefaults();

    // The canonical constructor of the record
    private final Constructor<T> constructor;
    // Array of arguments to the constructor, initialized with default values for primitives
    private final Object[] constructorArgsDefaults;
    // Map from component names to index into the constructors arguments.
    private final Map<String, Integer> componentIndices = new HashMap<>();

    RecordAdapter(Class<T> raw, Map<String, BoundField> boundFields, boolean blockInaccessible) {
      super(boundFields);
      constructor = ReflectionHelper.getCanonicalRecordConstructor(raw);

      if (blockInaccessible) {
        checkAccessible(null, constructor);
      } else {
        // Ensure the constructor is accessible
        ReflectionHelper.makeAccessible(constructor);
      }

      String[] componentNames = ReflectionHelper.getRecordComponentNames(raw);
      for (int i = 0; i < componentNames.length; i++) {
        componentIndices.put(componentNames[i], i);
      }
      Class<?>[] parameterTypes = constructor.getParameterTypes();

      // We need to ensure that we are passing non-null values to primitive fields in the constructor. To do this,
      // we create an Object[] where all primitives are initialized to non-null values.
      constructorArgsDefaults = new Object[parameterTypes.length];
      for (int i = 0; i < parameterTypes.length; i++) {
        // This will correctly be null for non-primitive types:
        constructorArgsDefaults[i] = PRIMITIVE_DEFAULTS.get(parameterTypes[i]);
      }
    }

    private static Map<Class<?>, Object> primitiveDefaults() {
      Map<Class<?>, Object> zeroes = new HashMap<>();
      zeroes.put(byte.class, (byte) 0);
      zeroes.put(short.class, (short) 0);
      zeroes.put(int.class, 0);
      zeroes.put(long.class, 0L);
      zeroes.put(float.class, 0F);
      zeroes.put(double.class, 0D);
      zeroes.put(char.class, '\0');
      zeroes.put(boolean.class, false);
      return zeroes;
    }

    @Override
    Object[] createAccumulator() {
      return constructorArgsDefaults.clone();
    }

    @Override
    void readField(Object[] accumulator, JsonReader in, BoundField field) throws IOException {
      // Obtain the component index from the name of the field backing it
      Integer componentIndex = componentIndices.get(field.fieldName);
      if (componentIndex == null) {
        throw new IllegalStateException(
            "Could not find the index in the constructor '" + ReflectionHelper.constructorToString(constructor) + "'"
                + " for field with name '" + field.fieldName + "',"
                + " unable to determine which argument in the constructor the field corresponds"
                + " to. This is unexpected behavior, as we expect the RecordComponents to have the"
                + " same names as the fields in the Java class, and that the order of the"
                + " RecordComponents is the same as the order of the canonical constructor parameters.");
      }
      field.readIntoArray(in, componentIndex, accumulator);
    }

    @Override
    T finalize(Object[] accumulator) {
      try {
        return constructor.newInstance(accumulator);
      } catch (IllegalAccessException e) {
        throw ReflectionHelper.createExceptionForUnexpectedIllegalAccess(e);
      }
      // Note: InstantiationException should be impossible because record class is not abstract;
      //  IllegalArgumentException should not be possible unless a bad adapter returns objects of the wrong type
      catch (InstantiationException | IllegalArgumentException e) {
        throw new RuntimeException(
            "Failed to invoke constructor '" + ReflectionHelper.constructorToString(constructor) + "'"
            + " with args " + Arrays.toString(accumulator), e);
      }
      catch (InvocationTargetException e) {
        throw new RuntimeException(
            "Failed to invoke constructor '" + ReflectionHelper.constructorToString(constructor) + "'"
            + " with args " + Arrays.toString(accumulator), e.getCause());
      }
    }
  }
}
