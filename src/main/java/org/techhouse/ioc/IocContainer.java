package org.techhouse.ioc;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.techhouse.ex.DependencyInjectionFailed;

public final class IocContainer {

    private static final IocContainer instance = new IocContainer();

    private final Map<String, Object> dependencies = new ConcurrentHashMap<>();

    private IocContainer() {
    }

    public static <T> T get(Class<T> clazz) {
        final var name = clazz.getName();
        final var found = IocContainer.instance.dependencies.get(name);
        if (found != null) {
            return clazz.cast(found);
        }
        try {
            final var created = clazz.getConstructor().newInstance();
            final var winner = IocContainer.instance.dependencies.putIfAbsent(name, created);
            return clazz.cast(winner != null ? winner : created);
        } catch (NoSuchMethodException | InvocationTargetException | InstantiationException
                | IllegalAccessException exception) {
            throw new DependencyInjectionFailed(exception);
        }
    }
}
