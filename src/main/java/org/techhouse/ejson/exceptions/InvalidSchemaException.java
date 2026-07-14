package org.techhouse.ejson.exceptions;

public class InvalidSchemaException extends RuntimeException {
    public InvalidSchemaException(String error) {
        super(error);
    }
}
