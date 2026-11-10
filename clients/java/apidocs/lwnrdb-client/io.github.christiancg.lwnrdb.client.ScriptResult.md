The outcome of `RUN_SCRIPT` or `CALL_PROCEDURE`.

@param value the script's result as plain Java: `Map`, `List`, `String`, `Number`, `Boolean` or `null`
@param logs the lines the script wrote through `console`
@param logsTruncated `true` when the server dropped log lines past its limit
@param runId the run's id, usable with `Admin.scripts().cancel(runId)` while it runs
