Builds a `TriggerDefinition`.

## events(TriggerEvent...)

@param events the write events to fire on
@return this builder

## timing(TriggerTiming)

@param timing before or after the write
@return this builder

## mode(TriggerMode)

@param mode per document or per batch
@return this builder

## allowCascade(boolean)

@param allowCascade `true` lets the trigger's own writes fire triggers
@return this builder

## enabled(boolean)

@param enabled `false` keeps it stored but silent
@return this builder

## ifVersion(long)

@param version the version the stored definition must have, or the save is refused
@return this builder

## build()

@return the definition
@throws IllegalArgumentException when no event was given
