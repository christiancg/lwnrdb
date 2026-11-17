The `System.Text.Json` options the client uses to map typed documents.

## CreateOptions(JsonSerializerOptions, TimeZoneInfo)
Builds options for typed documents from the caller's options. The caller's options are copied, never changed, and the result is read-only.

The result adds the converters for `DateTime`, `DateTimeOffset`, `TimeOnly`, `GeoPoint`, `Vector`, `[LwnrdbVector]` members and the guards against non-finite numbers and text that looks like a custom value, and it maps a member named `Id` to `_id` when no member already has that name.

@param userOptions the options to start from, or null for camel-case names and strict number handling
@param temporalZone the zone stored date-times are in, or null to refuse `DateTime` values that are not `Unspecified`
@returns the options

## Default
Options with camel-case names, strict number handling and no time zone.
