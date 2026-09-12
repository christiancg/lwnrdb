// Fixture stand-in for the corpus harness/assert.js: the same "properties on a function object"
// shape, which is exactly what Phase 0 of the harness plan unblocked.
function assert(mustBeTrue, message) {
    if (mustBeTrue === true) {
        return;
    }
    throw new Test262Error(message === undefined ? "Expected true but got something else" : message);
}

assert._isSameValue = function (a, b) {
    if (a === b) {
        return a !== 0 || 1 / a === 1 / b;
    }
    return a !== a && b !== b;
};

assert.sameValue = function (actual, expected, message) {
    if (assert._isSameValue(actual, expected)) {
        return;
    }
    throw new Test262Error("Expected SameValue(" + String(actual) + ", " + String(expected) + ") to be true. "
        + (message === undefined ? "" : message));
};

assert.throws = function (expectedErrorConstructor, func, message) {
    try {
        func();
    } catch (thrown) {
        if (thrown instanceof expectedErrorConstructor) {
            return;
        }
        throw new Test262Error("Thrown value was not an instance of the expected constructor");
    }
    throw new Test262Error(message === undefined ? "Expected a thrown error" : message);
};
