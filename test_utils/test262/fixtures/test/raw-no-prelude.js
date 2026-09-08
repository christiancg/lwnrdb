/*---
description: A raw test runs with no prelude and no strict directive.
flags: [raw]
---*/

if (1 !== 1) {
    throw new Error("raw fixture broken");
}
