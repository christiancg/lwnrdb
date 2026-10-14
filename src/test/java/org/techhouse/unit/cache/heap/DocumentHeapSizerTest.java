package org.techhouse.unit.cache.heap;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.CacheSizeEstimator;
import org.techhouse.cache.heap.DocumentHeapSizer;
import org.techhouse.cache.heap.HeapLayout;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.custom_types.CustomTypeFactory;
import org.techhouse.ejson.custom_types.JsonDateTime;
import org.techhouse.ejson.custom_types.JsonGeo;
import org.techhouse.ejson.custom_types.JsonTime;
import org.techhouse.ejson.custom_types.JsonVector;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.internal.KeyTable;
import org.techhouse.ioc.IocContainer;
import org.techhouse.utils.GeoPoint;

public class DocumentHeapSizerTest {
    private static final HeapLayout LAYOUT = new HeapLayout(8, 12, 4);
    private static final long JSON_OBJECT = 24L;
    private static final long SMALL_OBJECT_ARRAYS = 64L;
    private static final long PARSED_ONE_MEMBER_ARRAYS = 32L;
    private static final long JSON_STRING = 16L;
    private static final long JSON_NUMBER = 16L;
    private static final long JSON_ARRAY = 16L;
    private static final long ARRAY_LIST = 24L;
    private static final long TEN_SLOT_ARRAY = 56L;
    private static final long BOX = 16L;
    private static final long STRING_UP_TO_4_CHARS = 40L;
    private static final long STRING_OF_5_TO_12_CHARS = 48L;
    private static final long EMPTY_OBJECT = JSON_OBJECT;
    private static final long NESTING_LEVEL = EMPTY_OBJECT + SMALL_OBJECT_ARRAYS + STRING_UP_TO_4_CHARS;

    private final Map<String, Class<? extends JsonCustom<?>>> registeredBefore = new HashMap<>();

    @BeforeEach
    public void rememberTheRegistry() {
        registeredBefore.putAll(CustomTypeFactory.getCustomTypes());
    }

    @AfterEach
    public void restoreTheRegistry() {
        CustomTypeFactory.getCustomTypes().clear();
        CustomTypeFactory.getCustomTypes().putAll(registeredBefore);
    }

    private static String fresh(String key) {
        return String.valueOf(key.toCharArray());
    }

    private static long size(JsonBaseElement element) {
        return DocumentHeapSizer.sizeOf(element, LAYOUT);
    }

    @Test
    public void test_flat_object_sums_its_arrays_keys_and_values() {
        final var document = new JsonObject();
        document.addProperty(fresh("_id"), "c_000001");
        document.addProperty(fresh("name"), "abc");

        final var idMember = STRING_UP_TO_4_CHARS + JSON_STRING + STRING_OF_5_TO_12_CHARS;
        final var nameMember = STRING_UP_TO_4_CHARS + JSON_STRING + STRING_UP_TO_4_CHARS;
        assertEquals(EMPTY_OBJECT + SMALL_OBJECT_ARRAYS + idMember + nameMember, size(document));
    }

    @Test
    public void test_empty_object_has_no_arrays() {
        assertEquals(EMPTY_OBJECT, size(new JsonObject()));
    }

    @Test
    public void test_nested_object_and_array_capacity() {
        final var tags = new JsonArray();
        tags.add("a");
        tags.add("b");
        tags.add("c");
        final var address = new JsonObject();
        address.addProperty(fresh("zip"), 4321);
        final var document = new JsonObject();
        document.add(fresh("tags"), tags);
        document.add(fresh("addr"), address);

        final var tagsValue = JSON_ARRAY + ARRAY_LIST + TEN_SLOT_ARRAY + 3 * (JSON_STRING + STRING_UP_TO_4_CHARS);
        final var addressValue = EMPTY_OBJECT + SMALL_OBJECT_ARRAYS + STRING_UP_TO_4_CHARS + JSON_NUMBER;
        final var members = 2 * (STRING_UP_TO_4_CHARS) + tagsValue + addressValue;
        assertEquals(EMPTY_OBJECT + SMALL_OBJECT_ARRAYS + members, size(document));
    }

    @Test
    public void test_an_empty_array_has_no_backing_array() {
        assertEquals(JSON_ARRAY + ARRAY_LIST, size(new JsonArray()));
    }

    @Test
    public void test_numbers_hold_their_value_without_a_box() {
        assertEquals(JSON_NUMBER, size(new JsonNumber("5")));
        assertEquals(JSON_NUMBER, size(new JsonNumber("-128")));
        assertEquals(JSON_NUMBER, size(new JsonNumber("500")));
        assertEquals(JSON_NUMBER, size(new JsonNumber("1.5")));
        assertEquals(JSON_NUMBER, size(new JsonNumber(3_000_000_000L)));
        assertEquals(JSON_NUMBER, size(new JsonNumber(7L)));
        assertEquals(JSON_NUMBER, size(new JsonNumber((Number) null)));
    }

    @Test
    public void test_boolean_and_null_share_their_instances() {
        assertEquals(0L, size(JsonBoolean.of(true)));
        assertEquals(0L, size(JsonNull.INSTANCE));
        assertEquals(0L, size(null));
    }

    @Test
    public void test_a_document_holding_only_nulls_costs_its_entries() {
        final var document = new JsonObject();
        document.add(fresh("a"), JsonNull.INSTANCE);
        document.add(fresh("b"), JsonNull.INSTANCE);
        assertEquals(EMPTY_OBJECT + SMALL_OBJECT_ARRAYS + 2 * (STRING_UP_TO_4_CHARS), size(document));
    }

    @Test
    public void test_non_latin1_strings_double_their_backing_array() {
        final var latin1 = new JsonString("abcdef");
        final var utf16 = new JsonString("中文字符串中");
        assertEquals(JSON_STRING + LAYOUT.stringSize("abcdef"), size(latin1));
        assertEquals(JSON_STRING + LAYOUT.shallowSize(String.class) + LAYOUT.arraySize(1, 12), size(utf16));
    }

    @Test
    public void test_custom_types_count_spelling_and_parsed_value() {
        final var geo = new JsonGeo("#geo(1.5,2.5)");
        final var geoBase = LAYOUT.shallowSize(JsonGeo.class) + LAYOUT.stringSize(geo.getValue())
                + LAYOUT.shallowSize(GeoPoint.class);
        assertEquals(geoBase, size(geo));
        assertEquals(geoBase + LAYOUT.stringSize(geo.geoHash()), size(geo));

        final var vector = new JsonVector("#vector(0.1,0.2,0.3,0.4)");
        final var vectorBase = LAYOUT.shallowSize(JsonVector.class) + LAYOUT.stringSize(vector.getValue())
                + LAYOUT.arraySize(Double.BYTES, 4);
        assertEquals(vectorBase, size(vector));
        assertEquals(vectorBase + LAYOUT.stringSize(vector.simHash()), size(vector));

        final var dateTime = new JsonDateTime("#datetime(2024-05-06T07:08:09)");
        assertEquals(LAYOUT.shallowSize(JsonDateTime.class) + LAYOUT.stringSize(dateTime.getValue())
                + LAYOUT.shallowSize(LocalDateTime.class) + LAYOUT.shallowSize(LocalDate.class)
                + LAYOUT.shallowSize(LocalTime.class), size(dateTime));

        final var time = new JsonTime("#time(07:08:09)");
        assertEquals(LAYOUT.shallowSize(JsonTime.class) + LAYOUT.stringSize(time.getValue())
                + LAYOUT.shallowSize(LocalTime.class), size(time));
    }

    @Test
    public void test_an_unknown_registered_custom_type_falls_back_to_its_shallow_size() {
        CustomTypeFactory.registerCustomType(LocaleType.class);
        final var custom = CustomTypeFactory.getCustomTypeInstance("#locale(en)");

        assertEquals(LAYOUT.shallowSize(LocaleType.class) + LAYOUT.stringSize("#locale(en)")
                + LAYOUT.shallowSize(Locale.class), size(custom));
        final var unparsed = new LocaleType();
        assertEquals(LAYOUT.shallowSize(LocaleType.class) + LAYOUT.stringSize(unparsed.getValue()), size(unparsed),
                "a custom element with no parsed value costs only itself and its spelling");
    }

    @Test
    public void test_an_unknown_element_kind_costs_its_shallow_size() {
        final var element = new UnknownElement();
        assertEquals(LAYOUT.shallowSize(UnknownElement.class), size(element));
    }

    @Test
    public void test_indexed_values_are_sized_by_kind() {
        assertEquals(LAYOUT.stringSize("value"), DocumentHeapSizer.indexedValueSize("value", LAYOUT));
        assertEquals(BOX, DocumentHeapSizer.indexedValueSize(42.0, LAYOUT));
        assertEquals(0L, DocumentHeapSizer.indexedValueSize(Boolean.TRUE, LAYOUT));
        assertEquals(0L, DocumentHeapSizer.indexedValueSize(null, LAYOUT));
        final var geo = new JsonGeo("#geo(1.5,2.5)");
        assertEquals(size(geo), DocumentHeapSizer.indexedValueSize(geo, LAYOUT));
        assertEquals(LAYOUT.shallowSize(Locale.class), DocumentHeapSizer.indexedValueSize(Locale.ENGLISH, LAYOUT));
    }

    @Test
    public void test_a_deeply_nested_document_is_sized_without_overflowing() {
        var nested = new JsonObject();
        for (var depth = 0; depth < 500; depth++) {
            final var parent = new JsonObject();
            parent.add(fresh("n"), nested);
            nested = parent;
        }
        assertEquals(500 * NESTING_LEVEL + EMPTY_OBJECT, size(nested));
    }

    private static String canonicalKey(String text) {
        KeyTable.canonical(text);
        final var key = KeyTable.canonical(text);
        assertTrue(KeyTable.isCanonical(key), text + " entered the key table");
        return key;
    }

    private static JsonObject parsed(String json) {
        return IocContainer.get(EJson.class).fromJson(json, JsonObject.class);
    }

    private static String pinnedKey(String text) {
        for (var hit = 0; hit <= Globals.KEY_TABLE_PIN_HITS; hit++) {
            KeyTable.canonical(text);
        }
        final var key = KeyTable.canonical(text);
        assertTrue(KeyTable.isPinned(key), text + " was pinned after enough hits");
        return key;
    }

    @Test
    public void aSharedKeyIsChargedUntilItIsPinned() {
        final var name = "sizer-shared-" + System.nanoTime();
        final var key = canonicalKey(name);
        final var document = parsed("{\"" + name + "\":\"abc\"}");

        assertSame(key, document.entrySet().iterator().next().getKey());
        assertFalse(KeyTable.isPinned(key));
        assertEquals(
                EMPTY_OBJECT + PARSED_ONE_MEMBER_ARRAYS + LAYOUT.stringSize(key) + JSON_STRING + STRING_UP_TO_4_CHARS,
                size(document));
    }

    @Test
    public void aPinnedKeyCostsNothingAndIsNeverDisplaced() {
        final var name = "sizer-pinned-" + System.nanoTime();
        final var key = pinnedKey(name);
        final var document = parsed("{\"" + name + "\":\"abc\"}");
        for (var churn = 0; churn < 200_000; churn++) {
            final var other = "pin-churn-" + churn;
            KeyTable.canonical(other);
            KeyTable.canonical(other);
        }

        assertSame(key, document.entrySet().iterator().next().getKey());
        assertTrue(KeyTable.isPinned(key), "a pinned key outlives any amount of churn");
        assertEquals(EMPTY_OBJECT + PARSED_ONE_MEMBER_ARRAYS + JSON_STRING + STRING_UP_TO_4_CHARS, size(document));
    }

    @Test
    public void aDocumentsCachedEstimateKeepsItsFirstValueWhenAKeyIsPinnedLater() {
        final var name = "sizer-later-" + System.nanoTime();
        canonicalKey(name);
        final var entry = new DbEntry();
        entry.setData(parsed("{\"" + name + "\":\"abc\"}"));
        final var first = CacheSizeEstimator.estimateEntry(entry);
        pinnedKey(name);

        assertTrue(size(entry.getData()) < first, "the raw size drops once the key is pinned");
        assertEquals(first, CacheSizeEstimator.estimateEntry(entry),
                "the memo keeps the first estimate, so the add and remove of one entry always agree");
        assertEquals(first, entry.memoizeHeapBytes(1L), "a second estimate never replaces the first");
    }

    public static class LocaleType extends JsonCustom<Locale> {
        public LocaleType() {
            super();
        }

        @SuppressWarnings("unused")
        public LocaleType(String value) {
            super(value);
        }

        @Override
        public String getCustomTypeName() {
            return "locale";
        }

        @Override
        protected Locale parse() {
            return Locale.forLanguageTag(stringDataValue());
        }

        @Override
        public Integer compare(Locale another) {
            return customValue.toLanguageTag().compareTo(another.toLanguageTag());
        }
    }

    private static final class UnknownElement extends JsonBaseElement {
        @Override
        public JsonBaseElement deepCopy() {
            return this;
        }
    }

    @Test
    public void anObjectPastSixteenMembersAlsoPaysForItsIndex() {
        final var document = new JsonObject(17);
        for (var member = 0; member < 17; member++) {
            document.add(fresh("k" + member), JsonNull.INSTANCE);
        }

        final var arrays = 2 * LAYOUT.referenceArraySize(17);
        assertEquals(EMPTY_OBJECT + arrays + LAYOUT.arraySize(Integer.BYTES, 64) + 17 * STRING_UP_TO_4_CHARS,
                size(document));
    }
}
