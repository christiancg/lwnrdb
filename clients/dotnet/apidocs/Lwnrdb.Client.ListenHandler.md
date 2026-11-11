Helpers to build a `ListenHandler<T>`.

## Of<T>(Action<ListenUpdate<T>>)
Builds a handler from a synchronous callback.

@typeparam T the type of the results
@param onUpdate called with each update
@returns the handler
