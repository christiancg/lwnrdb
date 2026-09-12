package org.techhouse.unit.ops.req.validations;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.req.validations.RequestValidator;

public class ScriptingRequestValidatorTest {
    @Test
    public void validate_runScript_valid() {
        assertTrue(RequestValidator.validate(new org.techhouse.ops.req.RunScriptRequest("myDb", "return 1;", null))
                .isValid());
    }

    @Test
    public void validate_runScript_missingScript_returnsFail() {
        assertFalse(
                RequestValidator.validate(new org.techhouse.ops.req.RunScriptRequest("myDb", null, null)).isValid());
    }

    @Test
    public void validate_runScript_blankScript_returnsFail() {
        assertFalse(
                RequestValidator.validate(new org.techhouse.ops.req.RunScriptRequest("myDb", "   ", null)).isValid());
    }

    @Test
    public void validate_listTriggerRuns_withoutStatus_returnsOk() {
        assertTrue(RequestValidator.validate(new org.techhouse.ops.req.ListTriggerRunsRequest()).isValid());
    }

    @Test
    public void validate_listTriggerRuns_withKnownStatus_returnsOk() {
        final var request = new org.techhouse.ops.req.ListTriggerRunsRequest();
        request.setStatus("dead");
        assertTrue(RequestValidator.validate(request).isValid());
    }

    @Test
    public void validate_listTriggerRuns_withUnknownStatus_returnsFail() {
        final var request = new org.techhouse.ops.req.ListTriggerRunsRequest();
        request.setStatus("sideways");
        assertFalse(RequestValidator.validate(request).isValid());
    }

    @Test
    public void validate_resolveTriggerRun_withBlankRunId_returnsFail() {
        final var request = new org.techhouse.ops.req.ResolveTriggerRunRequest();
        request.setDecision("replay");
        assertFalse(RequestValidator.validate(request).isValid());
    }

    @Test
    public void validate_resolveTriggerRun_withUnknownDecision_returnsFail() {
        final var request = new org.techhouse.ops.req.ResolveTriggerRunRequest();
        request.setRunId("abc");
        request.setDecision("maybe");
        assertFalse(RequestValidator.validate(request).isValid());
    }

    @Test
    public void validate_resolveTriggerRun_withReplayDecision_returnsOk() {
        final var request = new org.techhouse.ops.req.ResolveTriggerRunRequest();
        request.setRunId("abc");
        request.setDecision("discard");
        assertTrue(RequestValidator.validate(request).isValid());
    }
}
