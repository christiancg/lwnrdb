package org.techhouse.ejson.internal;

import org.techhouse.ejson.*;
import org.techhouse.ejson.reflect.TypeToken;
import org.techhouse.ejson.stream.JsonReader;
import org.techhouse.ejson.stream.JsonWriter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * This class selects which fields and types to omit. It is configurable, supporting modifiers,
 * synthetic fields, anonymous and local classes and inner classes.
 *
 * <p>This class is a type adapter factory; types that are excluded will be
 * adapted to null. It may delegate to another type adapter if only one
 * direction is excluded.
 *
 * @author Joel Leitch
 * @author Jesse Wilson
 */
public final class Excluder implements TypeAdapterFactory, Cloneable {
  private static final double IGNORE_VERSIONS = -1.0d;
  public static final Excluder DEFAULT = new Excluder();

  private double version = IGNORE_VERSIONS;
  private int modifiers = Modifier.TRANSIENT | Modifier.STATIC;
  private boolean serializeInnerClasses = true;
  private List<ExclusionStrategy> serializationStrategies = Collections.emptyList();
  private List<ExclusionStrategy> deserializationStrategies = Collections.emptyList();

  @Override protected Excluder clone() {
    try {
      return (Excluder) super.clone();
    } catch (CloneNotSupportedException e) {
      throw new AssertionError(e);
    }
  }

  public Excluder withVersion(double ignoreVersionsAfter) {
    Excluder result = clone();
    result.version = ignoreVersionsAfter;
    return result;
  }

  public Excluder withModifiers(int... modifiers) {
    Excluder result = clone();
    result.modifiers = 0;
    for (int modifier : modifiers) {
      result.modifiers |= modifier;
    }
    return result;
  }

  public Excluder disableInnerClassSerialization() {
    Excluder result = clone();
    result.serializeInnerClasses = false;
    return result;
  }

  public Excluder withExclusionStrategy(ExclusionStrategy exclusionStrategy,
      boolean serialization, boolean deserialization) {
    Excluder result = clone();
    if (serialization) {
      result.serializationStrategies = new ArrayList<>(serializationStrategies);
      result.serializationStrategies.add(exclusionStrategy);
    }
    if (deserialization) {
      result.deserializationStrategies = new ArrayList<>(deserializationStrategies);
      result.deserializationStrategies.add(exclusionStrategy);
    }
    return result;
  }

  @Override public <T> TypeAdapter<T> create(final EJson eJson, final TypeToken<T> type) {
    Class<?> rawType = type.getRawType();
    boolean excludeClass = excludeClassChecks(rawType);

    final boolean skipSerialize = excludeClass || excludeClassInStrategy(rawType, true);
    final boolean skipDeserialize = excludeClass ||  excludeClassInStrategy(rawType, false);

    if (!skipSerialize && !skipDeserialize) {
      return null;
    }

    return new TypeAdapter<>() {
        /**
         * The delegate is lazily created because it may not be needed, and creating it may fail.
         */
        private TypeAdapter<T> delegate;

        @Override
        public T read(JsonReader in) throws IOException {
            if (skipDeserialize) {
                in.skipValue();
                return null;
            }
            return delegate().read(in);
        }

        @Override
        public void write(JsonWriter out, T value) throws IOException {
            if (skipSerialize) {
                out.nullValue();
                return;
            }
            delegate().write(out, value);
        }

        private TypeAdapter<T> delegate() {
            TypeAdapter<T> d = delegate;
            return d != null
                    ? d
                    : (delegate = eJson.getDelegateAdapter(Excluder.this, type));
        }
    };
  }

  public boolean excludeField(Field field, boolean serialize) {
    if ((modifiers & field.getModifiers()) != 0) {
      return true;
    }

    if (version != Excluder.IGNORE_VERSIONS) {
      return true;
    }

    if (field.isSynthetic()) {
      return true;
    }

    if (!serializeInnerClasses && isInnerClass(field.getType())) {
      return true;
    }

    if (isAnonymousOrNonStaticLocal(field.getType())) {
      return true;
    }

    List<ExclusionStrategy> list = serialize ? serializationStrategies : deserializationStrategies;
    if (!list.isEmpty()) {
      FieldAttributes fieldAttributes = new FieldAttributes(field);
      for (ExclusionStrategy exclusionStrategy : list) {
        if (exclusionStrategy.shouldSkipField(fieldAttributes)) {
          return true;
        }
      }
    }

    return false;
  }

  private boolean excludeClassChecks(Class<?> clazz) {
      if (version != Excluder.IGNORE_VERSIONS) {
          return true;
      }

      if (!serializeInnerClasses && isInnerClass(clazz)) {
          return true;
      }

      return isAnonymousOrNonStaticLocal(clazz);
  }

  public boolean excludeClass(Class<?> clazz, boolean serialize) {
      return excludeClassChecks(clazz) ||
              excludeClassInStrategy(clazz, serialize);
  }

  private boolean excludeClassInStrategy(Class<?> clazz, boolean serialize) {
      List<ExclusionStrategy> list = serialize ? serializationStrategies : deserializationStrategies;
      for (ExclusionStrategy exclusionStrategy : list) {
          if (exclusionStrategy.shouldSkipClass(clazz)) {
              return true;
          }
      }
      return false;
  }

  private boolean isAnonymousOrNonStaticLocal(Class<?> clazz) {
    return !Enum.class.isAssignableFrom(clazz) && !isStatic(clazz)
        && (clazz.isAnonymousClass() || clazz.isLocalClass());
  }

  private boolean isInnerClass(Class<?> clazz) {
    return clazz.isMemberClass() && !isStatic(clazz);
  }

  private boolean isStatic(Class<?> clazz) {
    return (clazz.getModifiers() & Modifier.STATIC) != 0;
  }

}
