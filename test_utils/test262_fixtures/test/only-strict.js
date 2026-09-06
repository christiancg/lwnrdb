/*---
description: onlyStrict runs once with a prepended directive; this only holds under strict mode.
flags: [onlyStrict]
---*/

assert.sameValue((function () { return this; })(), undefined, "strict this");
