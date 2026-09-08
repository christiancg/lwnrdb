/*---
description: noStrict is skipped, not failed - the engine is deliberately always strict.
flags: [noStrict]
---*/

assert.sameValue(typeof this, "object");
