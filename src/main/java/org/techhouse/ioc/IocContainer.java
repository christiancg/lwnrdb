package org.techhouse.ioc;

import org.techhouse.ex.DependencyInjectionFailed;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public class IocContainer {

    private final static IocContainer instance = new IocContainer();

    private final Map<String, Object> dependencies = new ConcurrentHashMap<>();

    private IocContainer() {
    }

    public static <T> T get(Class<T> clazz) {
        T targetedDependency;
        Object found = IocContainer.instance.dependencies.get(clazz.getName());
        if (found == null) {
            try {
                final var constructor = clazz.getConstructor();
                targetedDependency = constructor.newInstance();
                if (!IocContainer.instance.dependencies.containsKey(clazz.getName())) {
                    IocContainer.instance.dependencies.put(clazz.getName(), targetedDependency);
                } else {
                    targetedDependency = clazz.cast(IocContainer.instance.dependencies.get(clazz.getName()));
                }
            } catch (NoSuchMethodException | InvocationTargetException | InstantiationException | IllegalAccessException exception) {
                throw new DependencyInjectionFailed(exception);
            }
        } else {
            targetedDependency = clazz.cast(found);
        }
        return targetedDependency;
    }
}
