package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.builtins.DbModule;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;
import org.techhouse.unit.simplejs.host.FakeDatabaseAccess;

public class DbModuleArgumentTest {
    private final FakeDatabaseAccess fake = new FakeDatabaseAccess();
    private final JsObject db = DbModule.create(fake, null, null, null);

    private void call(String method, JsValue... args) {
        final var fn = (JsNativeFunction) db.get(method);
        fn.invoke(JsUndefined.getInstance(), List.of(args));
    }

    private void assertRefused(String expectedMessage, String method, JsValue... args) {
        final var refusal = assertThrows(TypeErrorException.class, () -> call(method, args));
        assertTrue(refusal.getMessage().contains(expectedMessage), refusal.getMessage());
        assertTrue(fake.calls.isEmpty(), fake.calls.toString());
    }

    @Test
    public void test_delete_without_an_id_is_a_type_error_and_reaches_no_database_call() {
        assertRefused("db.delete expects a string id", "delete", new JsString("d"), new JsString("c"));
    }

    @Test
    public void test_delete_with_a_number_id_is_a_type_error() {
        assertRefused("db.delete expects a string id", "delete", new JsString("d"), new JsString("c"), new JsNumber(5));
    }

    @Test
    public void test_delete_with_a_null_id_is_a_type_error() {
        assertRefused("db.delete expects a string id", "delete", new JsString("d"), new JsString("c"),
                JsNull.getInstance());
    }

    @Test
    public void test_find_by_id_with_an_object_id_is_a_type_error() {
        assertRefused("db.findById expects a string id", "findById", new JsString("d"), new JsString("c"),
                new JsObject());
    }

    @Test
    public void test_find_by_id_with_a_non_string_database_is_a_type_error() {
        assertRefused("db.findById expects a string database name", "findById", new JsNumber(1), new JsString("c"),
                new JsString("x"));
    }

    @Test
    public void test_save_with_a_non_string_collection_is_refused_before_the_payload_is_converted() {
        assertRefused("db.save expects a string collection name", "save", new JsString("d"), new JsNumber(7),
                new JsNumber(5));
    }

    @Test
    public void test_aggregate_with_a_missing_collection_is_a_type_error() {
        assertRefused("db.aggregate expects a string collection name", "aggregate", new JsString("d"));
    }

    @Test
    public void test_bulk_save_with_a_non_string_database_is_a_type_error() {
        assertRefused("db.bulkSave expects a string database name", "bulkSave", JsUndefined.getInstance(),
                new JsString("c"), new JsArray());
    }

    @Test
    public void test_list_collections_without_a_database_is_a_type_error() {
        assertRefused("db.listCollections expects a string database name", "listCollections");
    }

    @Test
    public void test_cursor_with_a_non_string_database_is_a_type_error() {
        assertRefused("db.cursor expects a string database name", "cursor", new JsObject(), new JsString("c"),
                new JsArray());
    }

    @Test
    public void test_string_arguments_still_reach_the_database_unchanged() {
        call("delete", new JsString("d"), new JsString("c"), new JsString("undefined"));
        call("findById", new JsString("d"), new JsString("c"), new JsString("5"));
        call("listCollections", new JsString("d"));
        assertEquals(List.of("delete:d/c/undefined", "findById:d/c/5", "listCollections:d"), fake.calls);
    }
}
