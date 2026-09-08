// `$DONE` exists only for `flags: [async]` tests, as on a real host — a non-async test asserts that
// globalThis has no own "$DONE", so this cannot join the unconditional prelude.
// A test that includes doneprintHandle.js redefines this in terms of print, which resolves to the
// same sink and the same sentinels.
var $DONE = function (error) {
    if (!error) {
        print("Test262:AsyncTestComplete");
    } else if (typeof error === "object" && error !== null && typeof error.name === "string") {
        print("Test262:AsyncTestFailure:" + error.name + ": " + error.message);
    } else {
        print("Test262:AsyncTestFailure:Test262Error: " + String(error));
    }
};
