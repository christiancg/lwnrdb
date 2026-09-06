// test262 expects the host to provide `print` (used by harness/doneprintHandle.js). SimpleJS has no
// `print`; it has `console.log`, wired to HostBindings.console(), which Test262Worker captures. The
// async verdict is read off that sink: "Test262:AsyncTestComplete" is a pass,
// "Test262:AsyncTestFailure:…" carries the failure message.
var print = function (message) {
    console.log(String(message));
};
