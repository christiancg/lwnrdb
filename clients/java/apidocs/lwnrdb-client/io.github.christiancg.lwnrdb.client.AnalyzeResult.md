The answer to an analyzed query: the rows plus the server's explanation of how it ran them.

Returned by `Query.analyze()`, which sends the pipeline with `"analyze": true`.

@param results the rows the pipeline produced
@param analysis the server's `analyzeResult`: the steps it ran, the indexes it used and the documents it scanned
