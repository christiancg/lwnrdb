package org.techhouse.ejson2.exceptions;

public class NoConstructorException extends RuntimeException {
    public NoConstructorException(Class<?> tClass) {
        super("Couldn't find a suitable constructor for class " + tClass.getName());
    }
}
