// test262 expects the host to provide `print` (used by harness/doneprintHandle.js) and, for
// `flags: [async]` tests, a `$DONE` function. SimpleJS has no `print`; it has `console.log`, wired to
// HostBindings.console(), which Test262Worker captures. The async verdict is read off that sink:
// "Test262:AsyncTestComplete" is a pass, "Test262:AsyncTestFailure:…" carries the failure message.
var print = function (message) {
    console.log(String(message));
};

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
