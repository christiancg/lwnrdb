# GeoPoint

A latitude and longitude, the server's `#geo(lat,lng)` value. Immutable.

## lat

The latitude, -90 to 90.

## lng

The longitude, -180 to 180.

## of

@param lat the latitude, -90 to 90
@param lng the longitude, -180 to 180
@returns the point
@throws RangeError on a coordinate out of range or not finite

## equals

@param other any value
@returns whether it is a `GeoPoint` with the same coordinates

## toString

@returns a readable form of the point
