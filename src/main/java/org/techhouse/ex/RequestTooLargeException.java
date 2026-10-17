package org.techhouse.ex;

import java.io.IOException;

public class RequestTooLargeException extends IOException {
    public RequestTooLargeException(long limit) {
        super("Request exceeds " + limit + " bytes");
    }
}
