package org.techhouse.unit.ioc;

import org.junit.jupiter.api.Test;
import org.techhouse.ex.DependencyInjectionFailed;
import org.techhouse.ioc.IocContainer;

import static org.junit.jupiter.api.Assertions.*;

public class IocContainerTest {
    // Get instance of a class that hasn't been registered returns new singleton instance
    @Test
    public void get_unregistered_class_returns_singleton() {
        String firstInstance = IocContainer.get(String.class);
        String secondInstance = IocContainer.get(String.class);

        assertNotNull(firstInstance);
        assertSame(firstInstance, secondInstance);
    }

    // Get instance of class without default constructor throws DependencyInjectionFailed
    @Test
    public void get_class_without_default_constructor_throws_exception() {
        assertThrows(DependencyInjectionFailed.class, () -> IocContainer.get(Integer.class));
    }
}