The Jackson module the client registers: it writes `GeoPoint`, `Vector`, `LocalDateTime`, `LocalTime`
and (with a temporal zone) zoned date-times as the server's custom values, and refuses plain strings the server
would read as custom values.

## LwnrdbModule()

Creates the module without a temporal zone: zoned date-times are refused.

## LwnrdbModule(ZoneId)

@param temporalZone the zone zoned date-times are converted to
