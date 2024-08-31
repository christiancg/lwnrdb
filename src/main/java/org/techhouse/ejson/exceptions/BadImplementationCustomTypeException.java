package org.techhouse.ejson.exceptions;

public class BadImplementationCustomTypeException extends RuntimeException {
    public BadImplementationCustomTypeException(String className, Exception e) {
        super("The custom type with class name " + className + " has not been properly implemented", e);
    }
}
