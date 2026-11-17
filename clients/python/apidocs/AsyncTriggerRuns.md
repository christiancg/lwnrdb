# AsyncTriggerRuns

Trigger runs waiting to run, and the dead ones that exhausted their attempts.

The sync class `TriggerRuns` has the same members; its methods block and return the value directly.

## list

Lists trigger runs.

Args:
    status: keeps only the runs in this status

## resolve

Replays or discards a trigger run.

Returns:
    whether the run was found and resolved
