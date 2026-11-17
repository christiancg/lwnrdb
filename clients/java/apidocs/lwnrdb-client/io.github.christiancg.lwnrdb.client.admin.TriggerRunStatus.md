The state of a trigger run.

## PENDING

Waiting for its next attempt.

## DEAD

Out of attempts; waits for `resolve`.

## STAGED

Recorded by a write whose transaction has not committed yet.
