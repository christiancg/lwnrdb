package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.techhouse.ops.ScriptAdmission;

final class AdmissionAssertions {
    private AdmissionAssertions() {
    }

    static void assertAdmitted(ScriptAdmission.Admission admission) {
        assertFalse(admission instanceof ScriptAdmission.Refusal, "expected a permit, got " + admission);
    }

    static void assertAdmitted(ScriptAdmission.Admission admission, String message) {
        assertFalse(admission instanceof ScriptAdmission.Refusal, message);
    }
}
