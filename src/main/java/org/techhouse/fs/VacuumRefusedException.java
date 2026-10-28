package org.techhouse.fs;

import java.io.IOException;

public class VacuumRefusedException extends IOException {
    public VacuumRefusedException(String message) {
        super(message);
    }
}
