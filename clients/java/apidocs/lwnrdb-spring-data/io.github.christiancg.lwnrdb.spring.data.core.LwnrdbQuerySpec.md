What a query selects: a filter, a sort, how many rows to skip and how many to return.

@param filter the filter, empty for all rows
@param sort the order; one property at most
@param skip rows to skip
@param limit rows to return, empty for all

## all()

@return a spec selecting everything

## of(Filter)

@param filter the filter
@return a spec selecting what it matches

## sorted(Sort)

@param order the order to add
@return the spec with it

## page(long, int)

@param offset rows to skip
@param size rows to return
@return the spec for that page

## limited(int)

@param maxResults the most rows to return
@return the spec limited to it, or to its own lower limit
