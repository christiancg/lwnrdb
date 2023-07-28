package org.techhouse.ex;

public class DirectoryNotFoundException extends RuntimeException {
    public DirectoryNotFoundException(String directory) {
        super("The specified directory couldn't be found or created: " + directory);
    }
}
