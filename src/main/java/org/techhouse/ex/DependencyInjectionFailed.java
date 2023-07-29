package org.techhouse.ex;

public class DependencyInjectionFailed extends RuntimeException {
    public DependencyInjectionFailed(Throwable exception) {
        super("The dependency injection failed", exception);
    }
}
