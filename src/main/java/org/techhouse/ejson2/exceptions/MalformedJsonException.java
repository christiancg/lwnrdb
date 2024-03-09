package org.techhouse.ejson2.exceptions;

public class MalformedJsonException extends RuntimeException {
    public MalformedJsonException(String error) {
        super(error);
    }
}
