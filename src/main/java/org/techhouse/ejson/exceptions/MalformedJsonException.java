package org.techhouse.ejson.exceptions;

public class MalformedJsonException extends RuntimeException {
    public MalformedJsonException(String error) {
        super(error);
    }
}
