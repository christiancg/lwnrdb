package org.techhouse.ejson.exceptions;

public class WrongFormatCustomTypeException extends RuntimeException {
    public WrongFormatCustomTypeException(String className, Exception e) {
        super("The format for the custom type with class name " + className + " is wrong.", e);
    }

    public WrongFormatCustomTypeException(String className) {
        super("The format for the custom type with class name " + className + " is wrong.");
    }
}
