/*---
description: A test that never returns is killed by the driver and recorded as a HANG.
---*/

// The driver's per-test timeout is below the worker's wall clock in --self-test, so this exercises
// the kill-and-respawn path. Each iteration does enough work that the instruction budget cannot be
// spent before the timeout fires — a tight empty loop would finish as a budget FAIL instead.
var sink = 0;
while (true) {
    sink += JSON.stringify({ a: [1, 2, 3, 4, 5, 6, 7, 8], b: "padding for the serializer" }).length;
}
