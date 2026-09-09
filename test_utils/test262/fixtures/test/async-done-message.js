/*---
description: $DONE with a message is a FAIL carrying that message.
flags: [async]
---*/

Promise.resolve(1).then(function () { $DONE("deliberate async failure"); });
