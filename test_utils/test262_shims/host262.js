// The minimal $262 host object test262 expects. Only what SimpleJS can honestly provide:
// `global`, and `detachArrayBuffer` on top of the ArrayBuffer transfer support. `createRealm`,
// `evalScript` and `agent` are absent by design — the tests that need them are excluded by
// config/test262-exclusions.txt rather than faked here.
var $262 = {
    global: globalThis,
    detachArrayBuffer: function (buffer) {
        buffer.transfer(0);
    }
};
