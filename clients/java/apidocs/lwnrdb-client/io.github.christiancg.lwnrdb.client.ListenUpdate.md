One delivery to a `ListenHandler`: the full current result of the listened query.

@param <T> the type the collection's codec decodes rows to
@param listenId the subscription the update belongs to
@param results the complete result set, not a diff
@param resultHash the server's SHA-256 of the result, which changes only when the result does
@param initial `true` for the first delivery, the answer to the `LISTEN` request itself
