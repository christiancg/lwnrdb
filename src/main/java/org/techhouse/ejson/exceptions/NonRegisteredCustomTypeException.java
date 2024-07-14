package org.techhouse.ejson.exceptions;

public class NonRegisteredCustomTypeException extends RuntimeException {
    public NonRegisteredCustomTypeException(String customTypeName) {
        super("The custom type " + customTypeName + " has not been registered");
    }
}
