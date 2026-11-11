One result of a listen: the query's full result at that moment.

@typeparam T the type of the results
@param ListenId the id of the listen
@param Results the full result of the query
@param ResultHash a hash of the result, equal for equal results
@param Initial true for the first result, sent when the listen started

## ListenUpdate(string, IReadOnlyList<T>, string, bool)
Creates an update.

@param ListenId the id of the listen
@param Results the full result of the query
@param ResultHash a hash of the result, equal for equal results
@param Initial true for the first result, sent when the listen started

## Initial
True for the first result, sent when the listen started.

## ListenId
The id of the listen.

## ResultHash
A hash of the result, equal for equal results.

## Results
The full result of the query at that moment.
