package org.techhouse.unit.ops.req.validations;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.req.CallProcedureRequest;
import org.techhouse.ops.req.DeleteProcedureRequest;
import org.techhouse.ops.req.DeleteScheduleRequest;
import org.techhouse.ops.req.DeleteTriggerRequest;
import org.techhouse.ops.req.ListTriggersRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.req.SaveScheduleRequest;
import org.techhouse.ops.req.SaveTriggerRequest;
import org.techhouse.ops.req.TestTriggerRequest;
import org.techhouse.ops.req.validations.RequestValidator;

public class ScriptRequestValidationTest {
    @Test
    public void test_save_procedure_valid() {
        assertTrue(RequestValidator.validate(new SaveProcedureRequest("myDb", "myProc", "return 1;")).isValid());
    }

    @Test
    public void test_save_procedure_rejects_the_admin_database() {
        assertFalse(RequestValidator.validate(new SaveProcedureRequest("admin", "myProc", "return 1;")).isValid());
    }

    @Test
    public void test_save_procedure_rejects_a_name_that_would_escape_its_database() {
        assertFalse(RequestValidator.validate(new SaveProcedureRequest("myDb", "../escaped", "return 1;")).isValid());
    }

    @Test
    public void test_save_procedure_rejects_a_missing_name() {
        assertFalse(RequestValidator.validate(new SaveProcedureRequest("myDb", null, "return 1;")).isValid());
    }

    @Test
    public void test_save_procedure_rejects_a_blank_script() {
        assertFalse(RequestValidator.validate(new SaveProcedureRequest("myDb", "myProc", "   ")).isValid());
    }

    @Test
    public void test_delete_procedure_valid() {
        assertTrue(RequestValidator.validate(new DeleteProcedureRequest("myDb", "myProc")).isValid());
    }

    @Test
    public void test_delete_procedure_rejects_a_bad_name() {
        assertFalse(RequestValidator.validate(new DeleteProcedureRequest("myDb", "no")).isValid());
    }

    @Test
    public void test_delete_procedure_rejects_a_bad_database() {
        assertFalse(RequestValidator.validate(new DeleteProcedureRequest("no", "myProc")).isValid());
    }

    @Test
    public void test_call_procedure_valid() {
        assertTrue(RequestValidator.validate(new CallProcedureRequest("myDb", "myProc", new JsonObject())).isValid());
    }

    @Test
    public void test_call_procedure_rejects_a_bad_name() {
        assertFalse(RequestValidator.validate(new CallProcedureRequest("myDb", "a b", new JsonObject())).isValid());
    }

    @Test
    public void test_save_trigger_valid() {
        final var request = new SaveTriggerRequest("myDb", "myColl", "myTrigger", List.of("CREATED"), "myProc");
        assertTrue(RequestValidator.validate(request).isValid());
    }

    @Test
    public void test_save_trigger_rejects_a_missing_collection() {
        final var request = new SaveTriggerRequest("myDb", null, "myTrigger", List.of("CREATED"), "myProc");
        assertFalse(RequestValidator.validate(request).isValid());
    }

    @Test
    public void test_save_trigger_rejects_a_reserved_collection() {
        final var request = new SaveTriggerRequest("myDb", "script_runs", "myTrigger", List.of("CREATED"), "myProc");
        assertFalse(RequestValidator.validate(request).isValid());
    }

    @Test
    public void test_save_trigger_rejects_a_bad_trigger_name() {
        final var request = new SaveTriggerRequest("myDb", "myColl", "x", List.of("CREATED"), "myProc");
        assertFalse(RequestValidator.validate(request).isValid());
    }

    @Test
    public void test_save_trigger_rejects_a_bad_procedure_name() {
        final var request = new SaveTriggerRequest("myDb", "myColl", "myTrigger", List.of("CREATED"), "x");
        assertFalse(RequestValidator.validate(request).isValid());
    }

    @Test
    public void test_delete_trigger_valid() {
        assertTrue(RequestValidator.validate(new DeleteTriggerRequest("myDb", "myColl", "myTrigger")).isValid());
    }

    @Test
    public void test_delete_trigger_rejects_a_bad_collection() {
        assertFalse(RequestValidator.validate(new DeleteTriggerRequest("myDb", "!!", "myTrigger")).isValid());
    }

    @Test
    public void test_delete_trigger_rejects_a_bad_name() {
        assertFalse(RequestValidator.validate(new DeleteTriggerRequest("myDb", "myColl", null)).isValid());
    }

    @Test
    public void test_list_triggers_without_a_collection_is_valid() {
        assertTrue(RequestValidator.validate(new ListTriggersRequest("myDb", null)).isValid());
    }

    @Test
    public void test_list_triggers_with_a_collection_is_valid() {
        assertTrue(RequestValidator.validate(new ListTriggersRequest("myDb", "myColl")).isValid());
    }

    @Test
    public void test_list_triggers_rejects_a_bad_collection() {
        assertFalse(RequestValidator.validate(new ListTriggersRequest("myDb", "??")).isValid());
    }

    @Test
    public void test_list_triggers_rejects_a_bad_database() {
        assertFalse(RequestValidator.validate(new ListTriggersRequest("admin", "myColl")).isValid());
    }

    @Test
    public void test_test_trigger_valid() {
        final var request = new TestTriggerRequest("myDb", "myColl", "myTrigger", "CREATED", new JsonObject());
        assertTrue(RequestValidator.validate(request).isValid());
    }

    @Test
    public void test_test_trigger_rejects_a_missing_document() {
        final var request = new TestTriggerRequest("myDb", "myColl", "myTrigger", "CREATED", null);
        assertFalse(RequestValidator.validate(request).isValid());
    }

    @Test
    public void test_test_trigger_rejects_a_bad_name() {
        final var request = new TestTriggerRequest("myDb", "myColl", "no", "CREATED", new JsonObject());
        assertFalse(RequestValidator.validate(request).isValid());
    }

    @Test
    public void test_test_trigger_rejects_a_bad_collection() {
        final var request = new TestTriggerRequest("myDb", "", "myTrigger", "CREATED", new JsonObject());
        assertFalse(RequestValidator.validate(request).isValid());
    }

    @Test
    public void test_save_schedule_valid() {
        assertTrue(RequestValidator.validate(new SaveScheduleRequest("myDb", "nightly", "myProc")).isValid());
    }

    @Test
    public void test_save_schedule_rejects_a_bad_procedure_name() {
        assertFalse(RequestValidator.validate(new SaveScheduleRequest("myDb", "nightly", "")).isValid());
    }

    @Test
    public void test_save_schedule_rejects_a_bad_database() {
        assertFalse(RequestValidator.validate(new SaveScheduleRequest(null, "nightly", "myProc")).isValid());
    }

    @Test
    public void test_delete_schedule_valid() {
        assertTrue(RequestValidator.validate(new DeleteScheduleRequest("myDb", "nightly")).isValid());
    }

    @Test
    public void test_delete_schedule_rejects_a_bad_name() {
        assertFalse(RequestValidator.validate(new DeleteScheduleRequest("myDb", "n")).isValid());
    }

    @Test
    public void test_delete_schedule_rejects_a_bad_database() {
        assertFalse(RequestValidator.validate(new DeleteScheduleRequest("a b", "nightly")).isValid());
    }
}
