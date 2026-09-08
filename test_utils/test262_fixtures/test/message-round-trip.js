/*---
description: An error message containing a newline and a quote survives the worker pipe.
---*/

throw new Test262Error('line one\nline "two"');
