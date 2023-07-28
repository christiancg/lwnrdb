package org.techhouse.ioc;

import org.techhouse.ex.DependencyNotFoundException;
import org.techhouse.fs.FileSystem;

import java.util.HashSet;
import java.util.Set;

public class IocContainer {

    private final static IocContainer instance = new IocContainer();

    private final Set<Object> dependencies = new HashSet<>();

    private IocContainer() {
        registerDependencies();
    }

    private void registerDependencies() {
        dependencies.add(new FileSystem());
    }

    public static <T> T get(Class<?> clazz) {
        return (T) IocContainer.instance.dependencies.stream().filter(x -> x.getClass().getTypeName().equals(clazz.getTypeName())).findFirst().orElseThrow(() -> new DependencyNotFoundException(clazz.getName()));
    }
}
