The server's custom value types.

## DateTime
`#datetime`, a date and time without a zone. Maps to `System.DateTime` of kind `Unspecified`.

## Geo
`#geo`, a latitude and longitude. Maps to `GeoPoint`.

## Time
`#time`, a time of day. Maps to `System.TimeOnly`.

## Vector
`#vector`, a list of numbers for nearest-neighbour search. Maps to `Vector`, or to a `double[]` or `float[]` member marked `[LwnrdbVector]`.
