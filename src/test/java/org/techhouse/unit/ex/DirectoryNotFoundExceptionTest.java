package org.techhouse.unit.ex;

import org.junit.jupiter.api.Test;
import org.techhouse.ex.DirectoryNotFoundException;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class DirectoryNotFoundExceptionTest {
    // Create exception with valid directory path string
    @Test
    public void test_create_exception_with_valid_directory_path() {
        String directoryPath = "/some/test/path";
        DirectoryNotFoundException exception = new DirectoryNotFoundException(directoryPath);

        assertEquals("The specified directory couldn't be found or created: /some/test/path", 
            exception.getMessage());
    }

    // Create exception with empty directory string
    @Test
    public void test_create_exception_with_empty_directory() {
        String emptyDirectory = "";
        DirectoryNotFoundException exception = new DirectoryNotFoundException(emptyDirectory);

        assertEquals("The specified directory couldn't be found or created: ", 
            exception.getMessage());
    }
}