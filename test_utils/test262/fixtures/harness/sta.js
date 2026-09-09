// Fixture stand-in for the corpus harness/sta.js, kept minimal on purpose: --self-test must work
// without a fetched corpus. The real run uses the corpus file verbatim.
function Test262Error(message) {
    this.message = message || "";
}

Test262Error.prototype.toString = function () {
    return "Test262Error: " + this.message;
};

Test262Error.thrower = function (message) {
    throw new Test262Error(message);
};
