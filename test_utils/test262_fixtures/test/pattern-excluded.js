/*---
description: A source-scan exclusion keeps eval tests out of the measurement.
---*/

assert.sameValue(eval("1 + 1"), 2);
