# nearest

Keeps the `k` rows whose vector field is nearest to a query vector. Approximate unless `.exact()`.

@param name the vector field path
@param query the vector
@param k how many, at least one
@returns the filter
