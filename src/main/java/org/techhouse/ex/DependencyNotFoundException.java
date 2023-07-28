package org.techhouse.ex;

public class DependencyNotFoundException extends RuntimeException {
    public DependencyNotFoundException(String dependency) {
        super("The specified dependency is not present: " + dependency + ". Maybe you forgot to register it?");
    }
}
