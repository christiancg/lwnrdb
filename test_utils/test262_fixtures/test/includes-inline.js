/*---
description: Inline includes are prepended in their declared order.
includes: [fixtureFirst.js, fixtureSecond.js]
---*/

assert.sameValue(FIXTURE_ORDER, "first-second");
