package org.techhouse.unit.ioc;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.ex.DependencyInjectionFailed;
import org.techhouse.ioc.IocContainer;

public class IocContainerTest {
    @Test
    public void get_unregistered_class_returns_singleton() {
        String firstInstance = IocContainer.get(String.class);
        String secondInstance = IocContainer.get(String.class);

        assertNotNull(firstInstance);
        assertSame(firstInstance, secondInstance);
    }

    @Test
    public void get_class_without_default_constructor_throws_exception() {
        assertThrows(DependencyInjectionFailed.class, () -> IocContainer.get(Integer.class));
    }
}
