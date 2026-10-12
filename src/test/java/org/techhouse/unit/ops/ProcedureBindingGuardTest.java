package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.techhouse.unit.ops.ProcedureBindingSupport.BOUND;
import static org.techhouse.unit.ops.ProcedureBindingSupport.DEFINER;
import static org.techhouse.unit.ops.ProcedureBindingSupport.INTRUDER;
import static org.techhouse.unit.ops.ProcedureBindingSupport.REFUSAL;
import static org.techhouse.unit.ops.ProcedureBindingSupport.bindSchedule;
import static org.techhouse.unit.ops.ProcedureBindingSupport.bindTrigger;
import static org.techhouse.unit.ops.ProcedureBindingSupport.installProcedure;
import static org.techhouse.unit.ops.ProcedureBindingSupport.refusal;
import static org.techhouse.unit.ops.ProcedureBindingSupport.saveProcedure;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.ex.MetadataReadException;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.ProcedureBindingGuard;
import org.techhouse.ops.ProcedureOperationHelper;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.DeleteProcedureRequest;
import org.techhouse.ops.req.DeleteUserRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ProcedureBindingGuardTest {
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);

    @BeforeEach
    public void setUp() throws Exception {
        ProcedureBindingSupport.setUp();
        installProcedure(BOUND, "return 1;");
    }

    @AfterEach
    public void tearDown() throws Exception {
        ProcedureBindingSupport.tearDown();
    }

    @Test
    public void test_an_unbound_procedure_is_free_for_any_caller() throws Exception {
        assertNull(refusal(BOUND, INTRUDER));
        assertEquals(OperationStatus.OK, saveProcedure(BOUND, "return 2;", INTRUDER).getStatus());
    }

    @Test
    public void test_a_trigger_bound_by_another_definer_refuses_save_disable_and_delete() throws Exception {
        bindTrigger("audit", BOUND, TriggerDefinition.TIMING_AFTER, DEFINER);

        assertEquals(REFUSAL, saveProcedure(BOUND, "return 2;", INTRUDER).getErrorCode());
        final var disable = new SaveProcedureRequest(TestGlobals.DB, BOUND, "return 1;");
        disable.setEnabled(false);
        assertEquals(REFUSAL, ProcedureOperationHelper.executeSave(disable, INTRUDER).getErrorCode());
        assertEquals(REFUSAL, ProcedureOperationHelper
                .executeDelete(new DeleteProcedureRequest(TestGlobals.DB, BOUND), INTRUDER).getErrorCode());
        assertTrue(cache.getProcedure(TestGlobals.DB, BOUND).isEnabled());
    }

    @Test
    public void test_a_before_hook_binding_counts() throws Exception {
        bindTrigger("gate", BOUND, TriggerDefinition.TIMING_BEFORE, DEFINER);

        assertEquals(REFUSAL, refusal(BOUND, INTRUDER).getErrorCode());
    }

    @Test
    public void test_a_schedule_binding_counts() throws Exception {
        bindSchedule("hourly", BOUND, DEFINER);

        assertEquals(REFUSAL, refusal(BOUND, INTRUDER).getErrorCode());
    }

    @Test
    public void test_the_definer_may_overwrite_its_own_binding() throws Exception {
        bindTrigger("audit", BOUND, TriggerDefinition.TIMING_AFTER, DEFINER);

        assertNull(refusal(BOUND, DEFINER));
        assertEquals(OperationStatus.OK, saveProcedure(BOUND, "return 2;", DEFINER).getStatus());
    }

    @Test
    public void test_a_database_owner_and_an_admin_may_overwrite() throws Exception {
        bindTrigger("audit", BOUND, TriggerDefinition.TIMING_AFTER, DEFINER);
        AdminOperationHelper.updateDatabaseOwners(TestGlobals.DB, List.of("owner"));
        assertEquals(OperationStatus.OK, TestUtils.createAdmin("root").getStatus());

        assertNull(refusal(BOUND, "owner"));
        assertNull(refusal(BOUND, "root"));
        assertEquals(OperationStatus.OK, saveProcedure(BOUND, "return 2;", "owner").getStatus());
        assertEquals(OperationStatus.OK, saveProcedure(BOUND, "return 3;", "root").getStatus());
    }

    @Test
    public void test_a_binding_by_the_caller_alone_does_not_refuse() throws Exception {
        bindTrigger("audit", BOUND, TriggerDefinition.TIMING_AFTER, INTRUDER);
        bindSchedule("hourly", BOUND, INTRUDER);

        assertNull(refusal(BOUND, INTRUDER));
    }

    @Test
    public void test_a_binding_by_two_definers_refuses_the_one_who_is_neither() throws Exception {
        bindTrigger("audit", BOUND, TriggerDefinition.TIMING_AFTER, DEFINER);
        bindSchedule("hourly", BOUND, "carol");

        assertEquals(REFUSAL, refusal(BOUND, DEFINER).getErrorCode());
        assertEquals(REFUSAL, refusal(BOUND, "carol").getErrorCode());
        assertEquals(REFUSAL, refusal(BOUND, INTRUDER).getErrorCode());
    }

    @Test
    public void test_a_procedure_imported_by_a_bound_procedure_is_protected() throws Exception {
        installProcedure("leaf", "export const v = 1;");
        installProcedure("middle", "import { v } from 'procedures/leaf'; export const w = v;");
        installProcedure("entry", "import { w } from 'procedures/middle'; return w;");
        installProcedure("unrelated", "return 0;");
        bindTrigger("audit", "entry", TriggerDefinition.TIMING_AFTER, DEFINER);

        assertEquals(REFUSAL, refusal("middle", INTRUDER).getErrorCode());
        assertEquals(REFUSAL, refusal("leaf", INTRUDER).getErrorCode());
        assertNull(refusal("unrelated", INTRUDER));
        assertNull(refusal("Leaf", INTRUDER));
    }

    @Test
    public void test_a_cyclic_import_graph_terminates() throws Exception {
        installProcedure("ring_a", "export const a = 1;");
        installProcedure("ring_b", "import { a } from 'procedures/ring_a'; export const b = a;");
        installProcedure("ring_a",
                "import { b } from 'procedures/ring_b'; import * as me from 'procedures/ring_a'; export const a = 1;");
        bindTrigger("audit", "ring_a", TriggerDefinition.TIMING_AFTER, DEFINER);

        assertEquals(REFUSAL, refusal("ring_b", INTRUDER).getErrorCode());
        assertNull(refusal("elsewhere", INTRUDER));
    }

    @Test
    public void test_an_opaque_importer_protects_every_name_including_new_ones() throws Exception {
        installProcedure("dyn", "const n = 'x'; await import('procedures/' + n); return 1;");
        bindTrigger("audit", "dyn", TriggerDefinition.TIMING_AFTER, DEFINER);

        assertEquals(REFUSAL, refusal(BOUND, INTRUDER).getErrorCode());
        assertEquals(REFUSAL, saveProcedure("brand_new", "return 1;", INTRUDER).getErrorCode());
        assertNull(cache.getProcedure(TestGlobals.DB, "brand_new"));
    }

    @Test
    public void test_a_null_acting_user_is_refused_when_a_foreign_binding_exists() throws Exception {
        bindTrigger("audit", BOUND, TriggerDefinition.TIMING_AFTER, DEFINER);

        assertEquals(REFUSAL, refusal(BOUND, null).getErrorCode());
    }

    @Test
    public void test_a_disabled_bound_procedure_still_counts() throws Exception {
        bindTrigger("audit", BOUND, TriggerDefinition.TIMING_AFTER, DEFINER);
        final var disable = new SaveProcedureRequest(TestGlobals.DB, BOUND, "return 1;");
        disable.setEnabled(false);
        assertEquals(OperationStatus.OK, ProcedureOperationHelper.executeSave(disable, DEFINER).getStatus());

        assertEquals(REFUSAL, refusal(BOUND, INTRUDER).getErrorCode());
    }

    @Test
    public void test_a_deleted_definers_binding_still_counts() throws Exception {
        assertEquals(OperationStatus.OK, TestUtils.createAdmin("root").getStatus());
        assertEquals(OperationStatus.OK, TestUtils.createAdmin("carol").getStatus());
        bindTrigger("audit", BOUND, TriggerDefinition.TIMING_AFTER, "carol");
        final var delete = new DeleteUserRequest();
        delete.setUsername("carol");
        assertEquals(OperationStatus.OK, UserOperationHelper.processDeleteUser(delete).getStatus());
        assertNull(cache.getAdminUserEntry("carol"));

        assertEquals(REFUSAL, refusal(BOUND, INTRUDER).getErrorCode());
    }

    @Test
    public void test_a_binding_whose_procedure_was_deleted_reaches_only_its_name() throws Exception {
        bindTrigger("audit", BOUND, TriggerDefinition.TIMING_AFTER, DEFINER);
        fs.deleteProcedure(TestGlobals.DB, BOUND);
        cache.removeProcedure(TestGlobals.DB, BOUND);

        assertEquals(REFUSAL, refusal(BOUND, INTRUDER).getErrorCode());
        assertNull(refusal("other", INTRUDER));
    }

    @Test
    public void test_the_refusal_names_no_definer() throws Exception {
        bindTrigger("audit", BOUND, TriggerDefinition.TIMING_AFTER, DEFINER);

        final var refused = ProcedureBindingGuard.refusal(OperationType.DELETE_PROCEDURE, TestGlobals.DB, BOUND,
                INTRUDER);

        assertNotNull(refused);
        assertEquals(OperationType.DELETE_PROCEDURE, refused.getType());
        assertEquals(OperationStatus.FORBIDDEN, refused.getStatus());
        assertEquals("Procedure '" + BOUND + "' is bound by a trigger or schedule that runs as another user; only"
                + " that user, a database owner or an admin may change it", refused.getMessage());
        assertFalse(refused.getMessage().contains(DEFINER));
        assertFalse(refused.getMessage().contains("audit"));
    }

    @Test
    public void test_an_unreadable_definition_propagates_as_a_read_failure() throws Exception {
        bindTrigger("audit", BOUND, TriggerDefinition.TIMING_AFTER, DEFINER);
        fs.writeTriggers(TestGlobals.DB, TestGlobals.COLL, "{not json");
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);

        assertThrows(MetadataReadException.class, () -> refusal(BOUND, INTRUDER));
    }
}
