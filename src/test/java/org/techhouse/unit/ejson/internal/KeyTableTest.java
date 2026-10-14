package org.techhouse.unit.ejson.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.internal.JsonParser;
import org.techhouse.ejson.internal.KeyTable;
import org.techhouse.ioc.IocContainer;
import org.techhouse.support.ParserOutcomes;

public class KeyTableTest {
    private static final int ONE_WINDOW = 8;

    @BeforeAll
    public static void registerCustomTypes() {
        IocContainer.get(EJson.class);
    }

    private static String fresh(String text) {
        return String.valueOf(text.toCharArray());
    }

    private static String lookup(KeyTable table, String text) {
        final var padded = "<" + text + ">";
        return table.lookup(padded, 1, padded.length() - 1);
    }

    private static List<String> keysOf(JsonObject object) {
        final var keys = new ArrayList<String>();
        for (final var member : object.entrySet()) {
            keys.add(member.getKey());
        }
        return keys;
    }

    private static JsonObject parse(String input, KeyTable table) {
        return JsonParser.parse(input, Integer.MAX_VALUE, Integer.MAX_VALUE, table).asJsonObject();
    }

    @Test
    public void repeatedKeysReturnTheSameInstance() {
        final var table = new KeyTable(Globals.KEY_TABLE_SLOTS);
        final var first = lookup(table, "name");

        assertSame(first, lookup(table, "name"));
        assertSame(first, table.lookup(new StringBuilder("xxnamexx"), 2, 6));
        assertTrue(table.holds(first));
        assertFalse(table.holds(fresh("name")));
    }

    @Test
    public void sharedTableCanonicalisesThroughTheStaticApi() {
        final var name = fresh("static-api-key");
        final var canonical = KeyTable.canonical(name);

        assertSame(canonical, KeyTable.canonical("(static-api-key)", 1, 15));
        assertTrue(KeyTable.isCanonical(canonical));
        assertFalse(KeyTable.isCanonical(null));
    }

    @Test
    public void longKeysAreNotCanonicalised() {
        final var table = new KeyTable(Globals.KEY_TABLE_SLOTS);
        final var atLimit = "k".repeat(Globals.KEY_TABLE_MAX_KEY_LENGTH);
        final var overLimit = "k".repeat(Globals.KEY_TABLE_MAX_KEY_LENGTH + 1);

        assertSame(lookup(table, atLimit), lookup(table, atLimit));
        final var once = lookup(table, overLimit);
        assertNotSame(once, lookup(table, overLimit));
        assertEquals(overLimit, once);
        assertFalse(table.holds(once));
        assertEquals(1, table.occupiedSlots());
    }

    @Test
    public void slotCountMustBeAPowerOfTwo() {
        assertThrows(IllegalArgumentException.class, () -> new KeyTable(12));
        assertThrows(IllegalArgumentException.class, () -> new KeyTable(1));
    }

    @Test
    public void differentFieldSetsShareOnlyTheirCommonNames() {
        final var table = new KeyTable(1024);
        final var contact = parse("{\"name\":\"a\",\"email\":\"b\"}", table);
        final var person = parse("{\"name\":\"c\",\"phone\":\"d\",\"address\":\"e\"}", table);
        final var place = parse("{\"address\":{\"city\":\"f\"}}", table);

        assertEquals(List.of("name", "email"), keysOf(contact));
        assertEquals(List.of("name", "phone", "address"), keysOf(person));
        assertEquals(List.of("address"), keysOf(place));
        assertSame(keysOf(contact).getFirst(), keysOf(person).getFirst());
        assertSame(keysOf(person).get(2), keysOf(place).getFirst());
        assertEquals(List.of("city"), keysOf(place.get("address").asJsonObject()));
        assertEquals("a", contact.get(fresh("name")).asJsonString().getValue());
        assertEquals("e", person.get(fresh("address")).asJsonString().getValue());
        assertFalse(contact.has("phone"));
        assertEquals(ParserOutcomes.legacyTree("{\"name\":\"c\",\"phone\":\"d\",\"address\":\"e\"}"), person);
        assertEquals(ParserOutcomes.legacyTree("{\"address\":{\"city\":\"f\"}}"), place);
    }

    private static List<String> fillOneWindow(KeyTable table) {
        final var residents = new ArrayList<String>();
        for (var index = 0; index < ONE_WINDOW; index++) {
            residents.add(lookup(table, "resident" + index));
        }
        return residents;
    }

    private static long stillHeld(KeyTable table, List<String> residents) {
        return residents.stream().filter(table::holds).count();
    }

    @Test
    public void coldSlotInAFullWindowIsReplaced() {
        final var table = new KeyTable(ONE_WINDOW);
        final var residents = fillOneWindow(table);
        for (var index = 0; index < ONE_WINDOW; index++) {
            if (index != 3) {
                assertSame(residents.get(index), lookup(table, "resident" + index));
            }
        }

        final var newcomer = lookup(table, "newcomer");

        assertTrue(table.holds(newcomer));
        assertFalse(table.holds(residents.get(3)));
        assertEquals(ONE_WINDOW - 1, stillHeld(table, residents));
        assertSame(newcomer, lookup(table, "newcomer"));
    }

    @Test
    public void newcomerIntoAHotWindowNeedsASecondMiss() {
        final var table = new KeyTable(ONE_WINDOW);
        final var residents = fillOneWindow(table);
        for (var index = 0; index < ONE_WINDOW; index++) {
            lookup(table, "resident" + index);
        }

        final var firstMiss = lookup(table, "newcomer");

        assertEquals("newcomer", firstMiss);
        assertFalse(table.holds(firstMiss));
        assertEquals(ONE_WINDOW, stillHeld(table, residents));

        final var secondMiss = lookup(table, "newcomer");

        assertNotSame(firstMiss, secondMiss);
        assertTrue(table.holds(secondMiss));
        assertEquals(ONE_WINDOW - 1, stillHeld(table, residents));
    }

    @Test
    public void hotKeysStayCanonicalUnderKeyChurn() {
        final var table = new KeyTable(Globals.KEY_TABLE_SLOTS);
        final var names = List.of("_id", "name", "email", "age", "address", "city", "zip", "phone", "tags", "created",
                "updated", "score", "active");
        final var canonical = new ArrayList<String>();
        for (final var name : names) {
            lookup(table, name);
            canonical.add(lookup(table, name));
        }
        for (var round = 0; round < 1_000_000; round++) {
            lookup(table, "user-" + round);
            for (var index = 0; index < names.size(); index++) {
                assertSame(canonical.get(index), lookup(table, names.get(index)), names.get(index));
            }
        }
        assertEquals(Globals.KEY_TABLE_SLOTS, table.capacity());
    }

    @Test
    public void replacedKeysStayValidInOlderDocuments() {
        final var table = new KeyTable(ONE_WINDOW);
        final var text = "{\"name\":\"x\",\"tags\":[1,2]}";
        final var older = parse(text, table);
        final var serialised = IocContainer.get(EJson.class).toJson(older);
        final var olderKey = keysOf(older).getFirst();
        var churn = 0;
        while (table.holds(olderKey)) {
            lookup(table, "churn" + churn);
            lookup(table, "churn" + churn);
            churn++;
        }

        final var newer = parse(text, table);

        assertNotSame(olderKey, keysOf(newer).getFirst());
        assertEquals(newer, older);
        assertEquals(newer.hashCode(), older.hashCode());
        assertEquals("x", older.get("name").asJsonString().getValue());
        assertEquals(serialised, IocContainer.get(EJson.class).toJson(older));
        assertEquals(IocContainer.get(EJson.class).toJson(newer), serialised);
    }

    @Test
    public void tableNeverGrowsPastItsSlots() {
        final var table = new KeyTable(Globals.KEY_TABLE_SLOTS);
        for (var index = 0; index < 1_000_000; index++) {
            lookup(table, "distinct-" + index);
        }

        assertEquals(Globals.KEY_TABLE_SLOTS, table.capacity());
        assertTrue(table.occupiedSlots() <= Globals.KEY_TABLE_SLOTS);
        assertTrue(table.occupiedSlots() > Globals.KEY_TABLE_SLOTS / 2);
    }
}
