Builds a `ScheduleDefinition`.

## cron(String)

@param expression a five-field cron expression
@return this builder

## interval(Duration)

@param interval the time between runs
@return this builder

## args(Document)

@param args the value the procedure imports as `args`
@return this builder

## timeout(Duration)

@param timeout how long one run may take
@return this builder

## description(String)

@param description free text
@return this builder

## enabled(boolean)

@param enabled `false` keeps it stored but idle
@return this builder

## ifVersion(long)

@param version the version the stored definition must have, or the save is refused
@return this builder

## build()

@return the definition
@throws IllegalArgumentException unless exactly one of cron and interval is set
