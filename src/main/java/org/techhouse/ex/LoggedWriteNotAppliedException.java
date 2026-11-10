package org.techhouse.ex;

import java.io.IOException;

public class LoggedWriteNotAppliedException extends IOException {
    public LoggedWriteNotAppliedException(String subject, IOException failure) {
        super("A write to " + subject + " was logged durably but could not be applied or taken back; it is applied"
                + " before the collection is next used, or at the next startup", failure);
    }

    public static boolean isCauseOf(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof LoggedWriteNotAppliedException) {
                return true;
            }
        }
        return false;
    }
}
