What `Triggers.TestAsync` answered.

@param Decision `accept`, `replace` or `reject`
@param Document the document the trigger would store
@param Logs the trigger's log lines
@param LogsTruncated whether log lines were dropped

## TriggerTestResult(string, Document, IReadOnlyList<string>, bool)
Creates the result.

@param Decision `accept`, `replace` or `reject`
@param Document the document the trigger would store
@param Logs the trigger's log lines
@param LogsTruncated whether log lines were dropped

## Decision
`accept`, `replace` or `reject`.

## Document
The document the trigger would store, or null.

## Logs
The trigger's log lines.

## LogsTruncated
True when the server dropped log lines to stay within its limit.
