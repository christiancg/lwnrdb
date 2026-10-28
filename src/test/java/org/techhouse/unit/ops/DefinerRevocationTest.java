package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.ScheduleRegistry;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.ScheduleDefinition;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.data.auth.ScriptPermissionLevel;
import org.techhouse.ejson.EJson;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.DefinerRevocation;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.admin.AdminRecordKey;
import org.techhouse.ops.admin.RestampedRecords;
import org.techhouse.ops.req.ChangePermissionsRequest;
import org.techhouse.ops.req.DeleteUserRequest;
import org.techhouse.ops.req.SetDatabaseOwnersRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.test.TriggerTestSupport;

public class DefinerRevocationTest {
    private static final String DEFINER = "revokeddefiner";
    private static final String OTHER = "otherdefiner";
    private static final Configuration configuration = Configuration.getInstance();
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private final ScheduleRegistry registry = IocContainer.get(ScheduleRegistry.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        IocContainer.get(ScheduleRegistry.class).clear();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "schedulesEnabled", true);
        TriggerTestSupport.clearTriggersAndProcedures();
        for (final var name : fs.metadataFiles().listScheduleNames(TestGlobals.DB)) {
            fs.metadataFiles().deleteSchedule(TestGlobals.DB, name);
        }
        cache.removeSchedulesForDatabase(TestGlobals.DB);
        registry.clear();
        grant(DEFINER, true);
        grant(OTHER, true);
    }

    @AfterEach
    void restoreOwners() throws Exception {
        AdminOperationHelper.updateDatabaseOwners(TestGlobals.DB, List.of());
    }

    private static ChangePermissionsRequest withoutScriptRun(String username) {
        final var change = new ChangePermissionsRequest();
        change.setUsername(username);
        change.setAdmin(false);
        change.setGlobalPermissions(new HashSet<>());
        change.setDatabasePermissions(new HashMap<>(java.util.Map.of(TestGlobals.DB, PermissionLevel.READ_WRITE)));
        change.setCollectionPermissions(new HashMap<>());
        change.setScriptPermissions(new HashMap<>());
        return change;
    }

    private static void grant(String username, boolean mayRun) throws Exception {
        final var databasePermissions = new HashMap<String, PermissionLevel>();
        databasePermissions.put(TestGlobals.DB, PermissionLevel.READ_WRITE);
        final var scriptPermissions = new HashMap<String, ScriptPermissionLevel>();
        scriptPermissions.put(TestGlobals.DB, mayRun ? ScriptPermissionLevel.RUN : ScriptPermissionLevel.NONE);
        AdminOperationHelper.saveUserEntry(new AdminUserEntry(username, "hash", false, new HashSet<>(),
                databasePermissions, new HashMap<>(), scriptPermissions));
    }

    private static TriggerDefinition trigger(String name, String definer, boolean enabled) {
        return new TriggerDefinition(name, new LinkedHashSet<>(Set.of(EventType.CREATED)), "job",
                TriggerDefinition.MODE_DOCUMENT, false, enabled, definer, 1L, 1L, 1L, definer);
    }

    private void installTriggers(TriggerDefinition... triggers) {
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL, List.of(triggers));
    }

    private void installSchedule(String name, String definer) throws Exception {
        final var definition = new ScheduleDefinition(name, "job", null, 60_000L, null, 0L, true, definer, null, 1L, 1L,
                1L, definer);
        fs.metadataFiles().writeSchedule(TestGlobals.DB, name, eJson.toJson(definition.toJsonObject()));
        cache.removeSchedule(TestGlobals.DB, name);
    }

    private TriggerDefinition storedTrigger(String name) {
        return cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL).stream()
                .filter(trigger -> trigger.getName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    public void test_a_revoked_definers_trigger_is_disabled_and_restamped() throws Exception {
        installTriggers(trigger("audit", DEFINER, true));
        grant(DEFINER, false);
        final var restamped = new RestampedRecords();

        DefinerRevocation.forDefiner(DEFINER, restamped);

        final var stored = storedTrigger("audit");
        assertFalse(stored.isEnabled());
        assertTrue(stored.getDisabledReason().contains("script RUN on database"), stored.getDisabledReason());
        assertTrue(fs.metadataFiles().readTriggers(TestGlobals.DB, TestGlobals.COLL).contains("disabledReason"));
        assertEquals(List.of(AdminRecordKey.triggers(TestGlobals.DB, TestGlobals.COLL)), restamped.keys());
    }

    @Test
    public void test_a_revoked_definers_schedule_is_disabled_and_restamped() throws Exception {
        installSchedule("nightly", DEFINER);
        grant(DEFINER, false);
        final var restamped = new RestampedRecords();

        DefinerRevocation.forDefiner(DEFINER, restamped);

        final var stored = cache.getSchedule(TestGlobals.DB, "nightly");
        assertFalse(stored.isEnabled());
        assertTrue(stored.getDisabledReason().contains("script RUN on database"), stored.getDisabledReason());
        assertEquals(List.of(AdminRecordKey.schedule(TestGlobals.DB, "nightly")), restamped.keys());
    }

    @Test
    public void test_other_definers_definitions_are_untouched() throws Exception {
        installTriggers(trigger("mine", DEFINER, true), trigger("theirs", OTHER, true));
        installSchedule("theirs", OTHER);
        grant(DEFINER, false);
        grant(OTHER, false);
        final var restamped = new RestampedRecords();

        DefinerRevocation.forDefiner(DEFINER, restamped);

        assertFalse(storedTrigger("mine").isEnabled());
        assertTrue(storedTrigger("theirs").isEnabled(), "only the changed user's definitions are re-checked");
        assertTrue(cache.getSchedule(TestGlobals.DB, "theirs").isEnabled());
    }

    @Test
    public void test_a_definer_who_still_holds_rights_keeps_its_definitions() throws Exception {
        installTriggers(trigger("audit", DEFINER, true));
        installSchedule("nightly", DEFINER);
        final var restamped = new RestampedRecords();

        DefinerRevocation.forDefiner(DEFINER, restamped);

        assertTrue(storedTrigger("audit").isEnabled());
        assertTrue(cache.getSchedule(TestGlobals.DB, "nightly").isEnabled());
        assertTrue(restamped.keys().isEmpty());
    }

    @Test
    public void test_an_already_disabled_definition_is_not_rewritten() throws Exception {
        installTriggers(trigger("audit", DEFINER, false));
        grant(DEFINER, false);
        final var restamped = new RestampedRecords();

        DefinerRevocation.forDefiner(DEFINER, restamped);

        assertEquals(1L, storedTrigger("audit").getVersion());
        assertTrue(restamped.keys().isEmpty());
    }

    @Test
    public void test_owner_removal_rechecks_every_definer_in_that_database() throws Exception {
        installTriggers(trigger("mine", DEFINER, true), trigger("theirs", OTHER, true));
        installSchedule("theirs", OTHER);
        grant(DEFINER, false);
        grant(OTHER, false);
        final var restamped = new RestampedRecords();

        DefinerRevocation.forDatabase(TestGlobals.DB, restamped);

        assertFalse(storedTrigger("mine").isEnabled());
        assertFalse(storedTrigger("theirs").isEnabled());
        assertFalse(cache.getSchedule(TestGlobals.DB, "theirs").isEnabled());
        assertEquals(List.of(AdminRecordKey.triggers(TestGlobals.DB, TestGlobals.COLL),
                AdminRecordKey.schedule(TestGlobals.DB, "theirs")), restamped.keys());
    }

    @Test
    public void test_an_unreadable_schedule_folder_is_logged_and_the_sweep_continues() throws Exception {
        installTriggers(trigger("audit", DEFINER, true));
        grant(DEFINER, false);
        final var schedulesFolder = Path.of(TestGlobals.PATH, TestGlobals.DB, Globals.SCHEDULES_FOLDER);
        if (Files.exists(schedulesFolder)) {
            TestUtils.deleteFolder(schedulesFolder.toFile());
        }
        Files.writeString(schedulesFolder, "not a folder");
        final var restamped = new RestampedRecords();
        try {
            DefinerRevocation.forDefiner(DEFINER, restamped);
        } finally {
            Files.delete(schedulesFolder);
        }

        assertFalse(storedTrigger("audit").isEnabled(), "the triggers are still re-checked");
        assertEquals(List.of(AdminRecordKey.triggers(TestGlobals.DB, TestGlobals.COLL)), restamped.keys());
    }

    @Test
    public void test_change_permissions_disables_the_definers_triggers() {
        installTriggers(trigger("audit", DEFINER, true));
        final var restamped = new RestampedRecords();

        final var response = UserOperationHelper.processChangePermissions(withoutScriptRun(DEFINER), restamped);

        assertEquals(OperationStatus.OK, response.getStatus());
        assertFalse(storedTrigger("audit").isEnabled());
        assertTrue(restamped.keys().contains(AdminRecordKey.triggers(TestGlobals.DB, TestGlobals.COLL)));
    }

    @Test
    public void test_delete_user_disables_the_definers_triggers() {
        installTriggers(trigger("audit", OTHER, true));
        final var delete = new DeleteUserRequest();
        delete.setUsername(OTHER);
        final var restamped = new RestampedRecords();

        final var response = UserOperationHelper.processDeleteUser(delete, restamped);

        assertEquals(OperationStatus.OK, response.getStatus());
        final var stored = storedTrigger("audit");
        assertFalse(stored.isEnabled());
        assertTrue(stored.getDisabledReason().contains("no longer exists"), stored.getDisabledReason());
        assertTrue(restamped.keys().contains(AdminRecordKey.triggers(TestGlobals.DB, TestGlobals.COLL)));
    }

    @Test
    public void test_a_refused_change_does_not_sweep() {
        installTriggers(trigger("audit", "ghostdefiner", true));
        final var restamped = new RestampedRecords();

        final var response = UserOperationHelper.processChangePermissions(withoutScriptRun("ghostdefiner"), restamped);

        assertEquals(OperationStatus.NOT_FOUND, response.getStatus());
        assertTrue(storedTrigger("audit").isEnabled(), "a refused change re-checks nothing");
        assertTrue(restamped.keys().isEmpty());
    }

    @Test
    public void test_removing_an_owner_disables_the_triggers_only_ownership_allowed() throws Exception {
        AdminOperationHelper.updateDatabaseOwners(TestGlobals.DB, List.of(DEFINER));
        grant(DEFINER, false);
        installTriggers(trigger("audit", DEFINER, true));
        final var owners = new SetDatabaseOwnersRequest(TestGlobals.DB);
        owners.setOwners(List.of(OTHER));

        final var response = IocContainer.get(OperationProcessor.class).processMessage(owners);

        assertEquals(OperationStatus.OK, response.getStatus());
        assertFalse(storedTrigger("audit").isEnabled());
    }

    @Test
    public void test_a_before_hook_is_never_disabled_by_a_revocation() {
        final var hook = new TriggerDefinition("hook", new LinkedHashSet<>(Set.of(EventType.CREATED)), "job",
                TriggerDefinition.MODE_DOCUMENT, TriggerDefinition.TIMING_BEFORE, false, true, DEFINER, 1L, 1L, 1L,
                DEFINER);
        installTriggers(hook);
        final var restamped = new RestampedRecords();

        UserOperationHelper.processChangePermissions(withoutScriptRun(DEFINER), restamped);

        assertTrue(storedTrigger("hook").isEnabled(), "before hooks are not re-checked against their definer");
        assertTrue(restamped.keys().isEmpty());
    }
}
