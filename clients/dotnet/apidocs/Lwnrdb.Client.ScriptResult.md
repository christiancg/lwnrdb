The outcome of running a script or calling a procedure.

@param Value the value the script returned, as a `Document`, list, string, number, boolean or null
@param Logs the lines the script logged
@param LogsTruncated true when log lines were dropped
@param RunId the id of the run, when the server reports one

## ScriptResult(object, IReadOnlyList<string>, bool, string)
Creates a result.

@param Value the value the script returned
@param Logs the lines the script logged
@param LogsTruncated true when log lines were dropped
@param RunId the id of the run, or null

## Logs
The lines the script logged.

## LogsTruncated
True when the server dropped log lines to stay within its limit.

## RunId
The id of the run, or null when the server does not report one.

## Value
The value the script returned: a `Document`, a list, a string, a number, a boolean or null.
