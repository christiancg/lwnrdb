Whether a read waits for the collection's read lock.

## COMMITTED

Waits for the collection's read lock, so the read never overlaps a write being applied.

## DIRTY

Skips the collection's read lock (`"dirtyRead": true`).
