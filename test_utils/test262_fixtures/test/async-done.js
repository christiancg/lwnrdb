/*---
description: An async test passes on the $DONE sentinel.
flags: [async]
---*/

Promise.resolve(1).then(function () { $DONE(); });
