package org.techhouse.ejson.internal;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;

/**
 * Do sneaky things to allocate objects without invoking their constructors.
 *
 * @author Joel Leitch
 * @author Jesse Wilson
 */
public abstract class UnsafeAllocator {
    public abstract <T> T newInstance(Class<T> c) throws Exception;

    public static final UnsafeAllocator INSTANCE = create();

    private static UnsafeAllocator create() {
        final var viaUnsafe = viaSunMiscUnsafe();
        return viaUnsafe != null ? viaUnsafe : unsupported();
    }

    private static UnsafeAllocator viaSunMiscUnsafe() {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field f = unsafeClass.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            final Object unsafe = f.get(null);
            final MethodHandle allocateInstance = MethodHandles.lookup()
                    .unreflect(unsafeClass.getMethod("allocateInstance", Class.class)).bindTo(unsafe)
                    .asType(MethodType.methodType(Object.class, Object.class));
            return new UnsafeAllocator() {
                @Override
                @SuppressWarnings("unchecked")
                public <T> T newInstance(Class<T> c) throws Exception {
                    return (T) HandleInvoker.apply(allocateInstance, c);
                }
            };
        } catch (Exception ignored) {
            return null;
        }
    }

    private static UnsafeAllocator unsupported() {
        return new UnsafeAllocator() {
            @Override
            public <T> T newInstance(Class<T> c) {
                throw new UnsupportedOperationException(
                        "Cannot allocate " + c + ". Usage of JDK sun.misc.Unsafe is enabled, "
                                + "but it could not be used. Make sure your runtime is configured correctly.");
            }
        };
    }
}
