package org.techhouse.ejson2.exceptions;

public class NoConstructorException extends RuntimeException {
    public NoConstructorException(Class<?> tClass) {
        super("Class " + tClass.getName() + " has no public constructor without arguments");
    }
}
