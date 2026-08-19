// The minimal $262 host object test262 expects. Only what SimpleJS can honestly provide:
// `global`, and `detachArrayBuffer` on top of the ArrayBuffer transfer support. `createRealm`,
// `evalScript` and `agent` are absent by design — the tests that need them are excluded by
// config/test262-exclusions.txt rather than faked here.
var $262 = {
    global: globalThis,
    detachArrayBuffer: function (buffer) {
        // Idempotent: a real host's native detach is a no-op on an already-detached buffer, but
        // `transfer(0)` throws in that case, since ArrayBuffer.prototype.transfer is a public API
        // with its own single-detach contract. A test whose comparator (or other repeated callback)
        // calls $262.detachArrayBuffer more than once on the same buffer must not see that as an
        // engine bug.
        if (!buffer.detached) {
            buffer.transfer(0);
        }
    }
};
