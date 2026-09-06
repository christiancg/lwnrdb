package org.techhouse.unit.simplejs.internal.interpreter;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.simplejs.host.SimpleHostBindings;

// Every collection constructor here is fed an endless iterable, so a lost abrupt completion is an
// infinite loop rather than a wrong answer: the wall-clock limit turns it into a ScriptTimeoutError
// (which the assertions reject) and the JUnit timeout on a separate thread is the outer guard.
@Timeout(value = 30, unit = SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
public class IterationCloseTest {
    private static final String ENDLESS = """
            let log = [];
            let item = [1, 2];
            let iterable = {};
            iterable[Symbol.iterator] = function() {
              return {
                next: function() { log.push('next'); return { value: item, done: false }; },
                return: function() { log.push('return'); return {}; }
              };
            };
            """;

    private final SimpleJs engine = new SimpleJs();

    private String run(String source) {
        final var result = engine.run(source,
                new SimpleHostBindings(new JsonObject(), null, null, new ResourceLimits(-1, 5000, -1)));
        assertFalse(result.isError(), () -> errorOf(result));
        return result.getValue().asJsonString().getValue();
    }

    private static String errorOf(ScriptResult result) {
        return result.getErrorName() + ": " + result.getErrorMessage();
    }

    private static String catching(String statement) {
        return """
                let caught = 'none';
                try { %s } catch (e) { caught = e.message; }
                return log.join('|') + '#' + caught;
                """.formatted(statement);
    }

    // IteratorStepValue marks the record done without closing it, so a `next` that throws must not be
    // followed by a `return` call — and the throw still has to end the loop.
    @Test
    public void test_map_next_failure_propagates_without_closing() {
        final var source = """
                let log = [];
                let iterable = {};
                iterable[Symbol.iterator] = function() {
                  return {
                    next: function() { log.push('next'); throw new Error('from-next'); },
                    return: function() { log.push('return'); return {}; }
                  };
                };
                """ + catching("new Map(iterable);");
        assertEquals("next#from-next", run(source));
    }

    @Test
    public void test_map_first_entry_get_failure_closes_the_iterator() {
        final var source = ENDLESS + """
                Object.defineProperty(item, 0, { get: function() { throw new Error('from-key'); } });
                """ + catching("new Map(iterable);");
        assertEquals("next|return#from-key", run(source));
    }

    @Test
    public void test_map_second_entry_get_failure_closes_the_iterator() {
        final var source = ENDLESS + """
                Object.defineProperty(item, 1, { get: function() { throw new Error('from-value'); } });
                """ + catching("new Map(iterable);");
        assertEquals("next|return#from-value", run(source));
    }

    @Test
    public void test_map_non_object_entry_closes_the_iterator() {
        final var source = ENDLESS + "item = 1;\n" + """
                let caught = 'none';
                try { new Map(iterable); } catch (e) { caught = '' + (e instanceof TypeError); }
                return log.join('|') + '#' + caught;
                """;
        assertEquals("next|return#true", run(source));
    }

    @Test
    public void test_map_set_failure_closes_the_iterator() {
        final var source = ENDLESS + "Map.prototype.set = function() { throw new Error('from-set'); };\n"
                + catching("new Map(iterable);");
        assertEquals("next|return#from-set", run(source));
    }

    // The pending abrupt completion wins over anything the close itself throws.
    @Test
    public void test_map_set_failure_survives_a_throwing_return() {
        final var source = """
                let log = [];
                let iterable = {};
                iterable[Symbol.iterator] = function() {
                  return {
                    next: function() { log.push('next'); return { value: [], done: false }; },
                    return: function() { log.push('return'); throw new TypeError('from-return'); }
                  };
                };
                Map.prototype.set = function() { throw new Error('from-set'); };
                """ + catching("new Map(iterable);");
        assertEquals("next|return#from-set", run(source));
    }

    // The adder is read off the receiver before the iterable is opened, so a non-callable `set` is a
    // TypeError raised without a single `next` call.
    @Test
    public void test_map_rejects_a_non_callable_adder_before_iterating() {
        final var source = ENDLESS + "Map.prototype.set = 1;\n" + """
                let caught = 'none';
                try { new Map(iterable); } catch (e) { caught = '' + (e instanceof TypeError); }
                return log.join('|') + '#' + caught;
                """;
        assertEquals("#true", run(source));
    }

    @Test
    public void test_weak_map_set_failure_closes_the_iterator() {
        final var source = ENDLESS + "WeakMap.prototype.set = function() { throw new Error('from-set'); };\n"
                + catching("new WeakMap(iterable);");
        assertEquals("next|return#from-set", run(source));
    }

    @Test
    public void test_weak_map_entry_get_failure_closes_the_iterator() {
        final var source = ENDLESS + """
                Object.defineProperty(item, 0, { get: function() { throw new Error('from-key'); } });
                """ + catching("new WeakMap(iterable);");
        assertEquals("next|return#from-key", run(source));
    }

    @Test
    public void test_set_add_failure_closes_the_iterator() {
        final var source = ENDLESS + "Set.prototype.add = function() { throw new Error('from-add'); };\n"
                + catching("new Set(iterable);");
        assertEquals("next|return#from-add", run(source));
    }

    @Test
    public void test_weak_set_add_failure_closes_the_iterator() {
        final var source = ENDLESS + "WeakSet.prototype.add = function() { throw new Error('from-add'); };\n"
                + catching("new WeakSet(iterable);");
        assertEquals("next|return#from-add", run(source));
    }

    // A primitive is not a valid weak value, so the builtin adder is the one that throws here.
    @Test
    public void test_weak_set_rejects_a_primitive_and_closes_the_iterator() {
        final var source = ENDLESS + "item = 1;\n" + """
                let caught = 'none';
                try { new WeakSet(iterable); } catch (e) { caught = '' + (e instanceof TypeError); }
                return log.join('|') + '#' + caught;
                """;
        assertEquals("next|return#true", run(source));
    }

    // Math.sumPrecise rejects a non-number without coercing it, and closes the iterator on the way out.
    @Test
    public void test_sum_precise_non_number_closes_the_iterator() {
        final var source = ENDLESS + """
                let coercions = 0;
                item = { valueOf: function() { coercions += 1; return 1; } };
                let caught = 'none';
                try { Math.sumPrecise(iterable); } catch (e) { caught = '' + (e instanceof TypeError); }
                return log.join('|') + '#' + caught + '#' + coercions;
                """;
        assertEquals("next|return#true#0", run(source));
    }

    @Test
    public void test_sum_precise_still_sums_a_finite_array() {
        assertEquals("1e-16", run("return '' + Math.sumPrecise([1e-16, 1, -1]);"));
    }

    @Test
    public void test_collections_still_build_from_an_array() {
        final var source = """
                let map = new Map([['a', 1], ['b', 2]]);
                let set = new Set(['x', 'y', 'x']);
                return map.get('a') + '|' + map.size + '|' + set.size + '|' + [...set].join(',');
                """;
        assertEquals("1|2|2|x,y", run(source));
    }

    // The constructors run GetIterator, so a replaced Array.prototype[Symbol.iterator] is observed.
    @Test
    public void test_set_honours_a_patched_array_iterator() {
        final var source = """
                Array.prototype[Symbol.iterator] = function() {
                  let done = false;
                  return { next: function() { const step = { value: 'patched', done }; done = true; return step; } };
                };
                return [...new Set(['a', 'b'])].join(',');
                """;
        assertEquals("patched", run(source));
    }
}
