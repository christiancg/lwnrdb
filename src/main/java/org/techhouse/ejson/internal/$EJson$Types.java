package org.techhouse.ejson.internal;

import java.io.Serial;
import java.io.Serializable;
import java.lang.reflect.*;
import java.util.*;

import static java.util.Objects.requireNonNull;
import static org.techhouse.ejson.internal.$EJson$Preconditions.checkArgument;

/**
 * Static methods for working with types.
 *
 * @author Bob Lee
 * @author Jesse Wilson
 */
public final class $EJson$Types {
  static final Type[] EMPTY_TYPE_ARRAY = new Type[] {};

  private $EJson$Types() {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns a new parameterized type, applying {@code typeArguments} to
   * {@code rawType} and enclosed by {@code ownerType}.
   *
   * @return a {@link Serializable serializable} parameterized type.
   */
  public static ParameterizedType newParameterizedTypeWithOwner(
      Type ownerType, Type rawType, Type... typeArguments) {
    return new ParameterizedTypeImpl(ownerType, rawType, typeArguments);
  }

  /**
   * Returns an array type whose elements are all instances of
   * {@code componentType}.
   *
   * @return a {@link Serializable serializable} generic array type.
   */
  public static GenericArrayType arrayOf(Type componentType) {
    return new GenericArrayTypeImpl(componentType);
  }

  /**
   * Returns a type that represents an unknown type that extends {@code bound}.
   * For example, if {@code bound} is {@code CharSequence.class}, this returns
   * {@code ? extends CharSequence}. If {@code bound} is {@code Object.class},
   * this returns {@code ?}, which is shorthand for {@code ? extends Object}.
   */
  public static WildcardType subtypeOf(Type bound) {
    Type[] upperBounds;
    if (bound instanceof WildcardType) {
      upperBounds = ((WildcardType) bound).getUpperBounds();
    } else {
      upperBounds = new Type[] { bound };
    }
    return new WildcardTypeImpl(upperBounds, EMPTY_TYPE_ARRAY);
  }

  /**
   * Returns a type that represents an unknown supertype of {@code bound}. For
   * example, if {@code bound} is {@code String.class}, this returns {@code ?
   * super String}.
   */
  public static WildcardType supertypeOf(Type bound) {
    Type[] lowerBounds;
    if (bound instanceof WildcardType) {
      lowerBounds = ((WildcardType) bound).getLowerBounds();
    } else {
      lowerBounds = new Type[] { bound };
    }
    return new WildcardTypeImpl(new Type[] { Object.class }, lowerBounds);
  }

  /**
   * Returns a type that is functionally equal but not necessarily equal
   * according to {@link Object#equals(Object) Object.equals()}. The returned
   * type is {@link Serializable}.
   */
  public static Type canonicalize(Type type) {
      return switch (type) {
          case Class<?> c -> c.isArray() ? new GenericArrayTypeImpl(canonicalize(c.getComponentType())) : c;
          case ParameterizedType p -> new ParameterizedTypeImpl(p.getOwnerType(),
                  p.getRawType(), p.getActualTypeArguments());
          case GenericArrayType g -> new GenericArrayTypeImpl(g.getGenericComponentType());
          case WildcardType w -> new WildcardTypeImpl(w.getUpperBounds(), w.getLowerBounds());
          case null, default ->
              // type is either serializable as-is or unsupported
                  type;
      };
  }

  public static Class<?> getRawType(Type type) {
      switch (type) {
          case Class<?> aClass -> {
              // type is a normal class.
              return aClass;
          }
          case ParameterizedType parameterizedType -> {

              // getRawType() returns Type instead of Class; that seems to be an API mistake,
              // see https://bugs.openjdk.org/browse/JDK-8250659
              Type rawType = parameterizedType.getRawType();
              checkArgument(rawType instanceof Class);
              return (Class<?>) rawType;
          }
          case GenericArrayType genericArrayType -> {
              Type componentType = genericArrayType.getGenericComponentType();
              return Array.newInstance(getRawType(componentType), 0).getClass();
          }
          case TypeVariable<?> ignored -> {
              // we could use the variable's bounds, but that won't work if there are multiple.
              // having a raw type that's more general than necessary is okay
              return Object.class;
          }
          case WildcardType wildcardType -> {
              Type[] bounds = wildcardType.getUpperBounds();
              // Currently the JLS only permits one bound for wildcards so using first bound is safe
              assert bounds.length == 1;
              return getRawType(bounds[0]);
          }
          case null, default -> {
              String className = type == null ? "null" : type.getClass().getName();
              throw new IllegalArgumentException("Expected a Class, ParameterizedType, or "
                      + "GenericArrayType, but <" + type + "> is of type " + className);
          }
      }
  }

  private static boolean equal(Object a, Object b) {
    return Objects.equals(a, b);
  }

  /**
   * Returns true if {@code a} and {@code b} are equal.
   */
  public static boolean equals(Type a, Type b) {
    if (a == b) {
      // also handles (a == null && b == null)
      return true;

    } else if (a instanceof Class) {
      // Class already specifies equals().
      return a.equals(b);

    } else if (a instanceof ParameterizedType pa) {
      if (!(b instanceof ParameterizedType pb)) {
        return false;
      }
      return equal(pa.getOwnerType(), pb.getOwnerType())
          && pa.getRawType().equals(pb.getRawType())
          && Arrays.equals(pa.getActualTypeArguments(), pb.getActualTypeArguments());

    } else if (a instanceof GenericArrayType ga) {
      if (!(b instanceof GenericArrayType gb)) {
        return false;
      }

      return equals(ga.getGenericComponentType(), gb.getGenericComponentType());

    } else if (a instanceof WildcardType wa) {
      if (!(b instanceof WildcardType wb)) {
        return false;
      }

      return Arrays.equals(wa.getUpperBounds(), wb.getUpperBounds())
          && Arrays.equals(wa.getLowerBounds(), wb.getLowerBounds());

    } else if (a instanceof TypeVariable<?> va) {
      if (!(b instanceof TypeVariable<?> vb)) {
        return false;
      }
      return va.getGenericDeclaration() == vb.getGenericDeclaration()
          && va.getName().equals(vb.getName());

    } else {
      // This isn't a type we support. Could be a generic array type, wildcard type, etc.
      return false;
    }
  }

  public static String typeToString(Type type) {
    return type instanceof Class ? ((Class<?>) type).getName() : type.toString();
  }

  /**
   * Returns the generic supertype for {@code supertype}. For example, given a class {@code
   * IntegerSet}, the result for when supertype is {@code Set.class} is {@code Set<Integer>} and the
   * result when the supertype is {@code Collection.class} is {@code Collection<Integer>}.
   */
  private static Type getGenericSupertype(Type context, Class<?> rawType, Class<?> supertype) {
    if (supertype == rawType) {
      return context;
    }

    // we skip searching through interfaces if unknown is an interface
    if (supertype.isInterface()) {
      Class<?>[] interfaces = rawType.getInterfaces();
      for (int i = 0, length = interfaces.length; i < length; i++) {
        if (interfaces[i] == supertype) {
          return rawType.getGenericInterfaces()[i];
        } else if (supertype.isAssignableFrom(interfaces[i])) {
          return getGenericSupertype(rawType.getGenericInterfaces()[i], interfaces[i], supertype);
        }
      }
    }

    // check our supertypes
    if (!rawType.isInterface()) {
      while (rawType != Object.class) {
        Class<?> rawSupertype = rawType.getSuperclass();
        if (rawSupertype == supertype) {
          return rawType.getGenericSuperclass();
        } else if (supertype.isAssignableFrom(rawSupertype)) {
          return getGenericSupertype(rawType.getGenericSuperclass(), rawSupertype, supertype);
        }
        rawType = rawSupertype;
      }
    }

    // we can't resolve this further
    return supertype;
  }

  /**
   * Returns the generic form of {@code supertype}. For example, if this is {@code
   * ArrayList<String>}, this returns {@code Iterable<String>} given the input {@code
   * Iterable.class}.
   *
   * @param supertype a superclass of, or interface implemented by, this.
   */
  private static Type getSupertype(Type context, Class<?> contextRawType, Class<?> supertype) {
    if (context instanceof WildcardType) {
      // wildcards are useless for resolving supertypes. As the upper bound has the same raw type, use it instead
      Type[] bounds = ((WildcardType)context).getUpperBounds();
      // Currently the JLS only permits one bound for wildcards so using first bound is safe
      assert bounds.length == 1;
      context = bounds[0];
    }
    checkArgument(supertype.isAssignableFrom(contextRawType));
    return resolve(context, contextRawType,
        $EJson$Types.getGenericSupertype(context, contextRawType, supertype));
  }

  /**
   * Returns the component type of this array type.
   * @throws ClassCastException if this type is not an array.
   */
  public static Type getArrayComponentType(Type array) {
    return array instanceof GenericArrayType
        ? ((GenericArrayType) array).getGenericComponentType()
        : ((Class<?>) array).getComponentType();
  }

  /**
   * Returns the element type of this collection type.
   * @throws IllegalArgumentException if this type is not a collection.
   */
  public static Type getCollectionElementType(Type context, Class<?> contextRawType) {
    Type collectionType = getSupertype(context, contextRawType, Collection.class);

    if (collectionType instanceof ParameterizedType) {
      return ((ParameterizedType) collectionType).getActualTypeArguments()[0];
    }
    return Object.class;
  }

  /**
   * Returns a two element array containing this map's key and value types in
   * positions 0 and 1 respectively.
   */
  public static Type[] getMapKeyAndValueTypes(Type context, Class<?> contextRawType) {
    /*
     * Work around a problem with the declaration of java.util.Properties. That
     * class should extend Hashtable<String, String>, but it's declared to
     * extend Hashtable<Object, Object>.
     */
    if (context == Properties.class) {
      return new Type[] { String.class, String.class };
    }

    Type mapType = getSupertype(context, contextRawType, Map.class);
    if (mapType instanceof ParameterizedType mapParameterizedType) {
      return mapParameterizedType.getActualTypeArguments();
    }
    return new Type[] { Object.class, Object.class };
  }

  public static Type resolve(Type context, Class<?> contextRawType, Type toResolve) {

    return resolve(context, contextRawType, toResolve, new HashMap<>());
  }

  private static Type resolve(Type context, Class<?> contextRawType, Type toResolve,
                              Map<TypeVariable<?>, Type> visitedTypeVariables) {
    // this implementation is made a little more complicated in an attempt to avoid object-creation
    TypeVariable<?> resolving = null;
      label:
      while (true) {
          Type finalToResolve = toResolve;
          switch (toResolve) {
              case TypeVariable<?> typeVariable:
                  Type previouslyResolved = visitedTypeVariables.get(typeVariable);
                  if (previouslyResolved != null) {
                      // cannot reduce due to infinite recursion
                      return (previouslyResolved == Void.TYPE) ? toResolve : previouslyResolved;
                  }

                  // Insert a placeholder to mark the fact that we are in the process of resolving this type
                  visitedTypeVariables.put(typeVariable, Void.TYPE);
                  if (resolving == null) {
                      resolving = typeVariable;
                  }

                  toResolve = resolveTypeVariable(context, contextRawType, typeVariable);
                  if (toResolve == typeVariable) {
                      break label;
                  }

                  break;
              case Class<?> original when ((Class<?>) finalToResolve).isArray(): {
                  Type componentType = original.getComponentType();
                  Type newComponentType = resolve(context, contextRawType, componentType, visitedTypeVariables);
                  toResolve = equal(componentType, newComponentType)
                          ? original
                          : arrayOf(newComponentType);
                  break label;

              }
              case GenericArrayType original: {
                  Type componentType = original.getGenericComponentType();
                  Type newComponentType = resolve(context, contextRawType, componentType, visitedTypeVariables);
                  toResolve = equal(componentType, newComponentType)
                          ? original
                          : arrayOf(newComponentType);
                  break label;

              }
              case ParameterizedType original:
                  Type ownerType = original.getOwnerType();
                  Type newOwnerType = resolve(context, contextRawType, ownerType, visitedTypeVariables);
                  boolean changed = !equal(newOwnerType, ownerType);

                  Type[] args = original.getActualTypeArguments();
                  for (int t = 0, length = args.length; t < length; t++) {
                      Type resolvedTypeArgument = resolve(context, contextRawType, args[t], visitedTypeVariables);
                      if (!equal(resolvedTypeArgument, args[t])) {
                          if (!changed) {
                              args = args.clone();
                              changed = true;
                          }
                          args[t] = resolvedTypeArgument;
                      }
                  }

                  toResolve = changed
                          ? newParameterizedTypeWithOwner(newOwnerType, original.getRawType(), args)
                          : original;
                  break label;

              case WildcardType original:
                  Type[] originalLowerBound = original.getLowerBounds();
                  Type[] originalUpperBound = original.getUpperBounds();

                  if (originalLowerBound.length == 1) {
                      Type lowerBound = resolve(context, contextRawType, originalLowerBound[0], visitedTypeVariables);
                      if (lowerBound != originalLowerBound[0]) {
                          toResolve = supertypeOf(lowerBound);
                          break label;
                      }
                  } else if (originalUpperBound.length == 1) {
                      Type upperBound = resolve(context, contextRawType, originalUpperBound[0], visitedTypeVariables);
                      if (upperBound != originalUpperBound[0]) {
                          toResolve = subtypeOf(upperBound);
                          break label;
                      }
                  }
                  toResolve = original;
                  break label;

              case null:
              default:
                  break label;
          }
      }
      // ensure that any in-process resolution gets updated with the final result
    if (resolving != null) {
      visitedTypeVariables.put(resolving, toResolve);
    }
    return toResolve;
  }

  private static Type resolveTypeVariable(Type context, Class<?> contextRawType, TypeVariable<?> unknown) {
    Class<?> declaredByRaw = declaringClassOf(unknown);

    // we can't reduce this further
    if (declaredByRaw == null) {
      return unknown;
    }

    Type declaredBy = getGenericSupertype(context, contextRawType, declaredByRaw);
    if (declaredBy instanceof ParameterizedType) {
      int index = indexOf(declaredByRaw.getTypeParameters(), unknown);
      return ((ParameterizedType) declaredBy).getActualTypeArguments()[index];
    }

    return unknown;
  }

  private static int indexOf(Object[] array, Object toFind) {
    for (int i = 0, length = array.length; i < length; i++) {
      if (toFind.equals(array[i])) {
        return i;
      }
    }
    throw new NoSuchElementException();
  }

  /**
   * Returns the declaring class of {@code typeVariable}, or {@code null} if it was not declared by
   * a class.
   */
  private static Class<?> declaringClassOf(TypeVariable<?> typeVariable) {
    GenericDeclaration genericDeclaration = typeVariable.getGenericDeclaration();
    return genericDeclaration instanceof Class
        ? (Class<?>) genericDeclaration
        : null;
  }

  static void checkNotPrimitive(Type type) {
    checkArgument(!(type instanceof Class<?>) || !((Class<?>) type).isPrimitive());
  }

  /**
   * Whether an {@linkplain ParameterizedType#getOwnerType() owner type} must be specified when
   * constructing a {@link ParameterizedType} for {@code rawType}.
   *
   * <p>Note that this method might not require an owner type for all cases where Java reflection
   * would create parameterized types with owner type.
   */
  public static boolean requiresOwnerType(Type rawType) {
    if (rawType instanceof Class<?> rawTypeAsClass) {
      return !Modifier.isStatic(rawTypeAsClass.getModifiers())
          && rawTypeAsClass.getDeclaringClass() != null;
    }
    return false;
  }

  private static final class ParameterizedTypeImpl implements ParameterizedType, Serializable {
    private final Type ownerType;
    private final Type rawType;
    private final Type[] typeArguments;

    public ParameterizedTypeImpl(Type ownerType, Type rawType, Type... typeArguments) {
      // the ParameterizedType interface and https://bugs.openjdk.org/browse/JDK-8250659
      requireNonNull(rawType);
      if (ownerType == null && requiresOwnerType(rawType)) {
        throw new IllegalArgumentException("Must specify owner type for " + rawType);
      }

      this.ownerType = ownerType == null ? null : canonicalize(ownerType);
      this.rawType = canonicalize(rawType);
      this.typeArguments = typeArguments.clone();
      for (int t = 0, length = this.typeArguments.length; t < length; t++) {
        requireNonNull(this.typeArguments[t]);
        checkNotPrimitive(this.typeArguments[t]);
        this.typeArguments[t] = canonicalize(this.typeArguments[t]);
      }
    }

    @Override public Type[] getActualTypeArguments() {
      return typeArguments.clone();
    }

    @Override public Type getRawType() {
      return rawType;
    }

    @Override public Type getOwnerType() {
      return ownerType;
    }

    @Override public boolean equals(Object other) {
      return other instanceof ParameterizedType
          && $EJson$Types.equals(this, (ParameterizedType) other);
    }

    private static int hashCodeOrZero(Object o) {
      return o != null ? o.hashCode() : 0;
    }

    @Override public int hashCode() {
      return Arrays.hashCode(typeArguments)
          ^ rawType.hashCode()
          ^ hashCodeOrZero(ownerType);
    }

    @Override public String toString() {
      int length = typeArguments.length;
      if (length == 0) {
        return typeToString(rawType);
      }

      StringBuilder stringBuilder = new StringBuilder(30 * (length + 1));
      stringBuilder.append(typeToString(rawType)).append("<").append(typeToString(typeArguments[0]));
      for (int i = 1; i < length; i++) {
        stringBuilder.append(", ").append(typeToString(typeArguments[i]));
      }
      return stringBuilder.append(">").toString();
    }

    @Serial
    private static final long serialVersionUID = 0;
  }

  private static final class GenericArrayTypeImpl implements GenericArrayType, Serializable {
    private final Type componentType;

    public GenericArrayTypeImpl(Type componentType) {
      requireNonNull(componentType);
      this.componentType = canonicalize(componentType);
    }

    @Override public Type getGenericComponentType() {
      return componentType;
    }

    @Override public boolean equals(Object o) {
      return o instanceof GenericArrayType
          && $EJson$Types.equals(this, (GenericArrayType) o);
    }

    @Override public int hashCode() {
      return componentType.hashCode();
    }

    @Override public String toString() {
      return typeToString(componentType) + "[]";
    }

    @Serial
    private static final long serialVersionUID = 0;
  }

  /**
   * The WildcardType interface supports multiple upper bounds and multiple
   * lower bounds. We only support what the target Java version supports - at most one
   * bound, see also <a href="https://bugs.openjdk.java.net/browse/JDK-8250660">...</a>. If a lower bound
   * is set, the upper bound must be Object.class.
   */
  private static final class WildcardTypeImpl implements WildcardType, Serializable {
    private final Type upperBound;
    private final Type lowerBound;

    public WildcardTypeImpl(Type[] upperBounds, Type[] lowerBounds) {
      checkArgument(lowerBounds.length <= 1);
      checkArgument(upperBounds.length == 1);

      if (lowerBounds.length == 1) {
        requireNonNull(lowerBounds[0]);
        checkNotPrimitive(lowerBounds[0]);
        checkArgument(upperBounds[0] == Object.class);
        this.lowerBound = canonicalize(lowerBounds[0]);
        this.upperBound = Object.class;

      } else {
        requireNonNull(upperBounds[0]);
        checkNotPrimitive(upperBounds[0]);
        this.lowerBound = null;
        this.upperBound = canonicalize(upperBounds[0]);
      }
    }

    @Override public Type[] getUpperBounds() {
      return new Type[] { upperBound };
    }

    @Override public Type[] getLowerBounds() {
      return lowerBound != null ? new Type[] { lowerBound } : EMPTY_TYPE_ARRAY;
    }

    @Override public boolean equals(Object other) {
      return other instanceof WildcardType
          && $EJson$Types.equals(this, (WildcardType) other);
    }

    @Override public int hashCode() {
      // this equals Arrays.hashCode(getLowerBounds()) ^ Arrays.hashCode(getUpperBounds());
      return (lowerBound != null ? 31 + lowerBound.hashCode() : 1)
          ^ (31 + upperBound.hashCode());
    }

    @Override public String toString() {
      if (lowerBound != null) {
        return "? super " + typeToString(lowerBound);
      } else if (upperBound == Object.class) {
        return "?";
      } else {
        return "? extends " + typeToString(upperBound);
      }
    }

    @Serial
    private static final long serialVersionUID = 0;
  }
}
