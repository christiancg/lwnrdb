package org.techhouse.ex;

import java.util.List;

public class InvalidConfigurationException extends RuntimeException {
    public InvalidConfigurationException(List<String> errors) {
        super("Invalid configuration:" + System.lineSeparator() +
                String.join(System.lineSeparator(), errors));
    }
}
