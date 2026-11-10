A scheduled procedure to save. It takes exactly one of `cron` and `interval`.

## builder(String, String)

@param name the schedule's name
@param procedureName the procedure it calls
@return a builder

## name()

@return the name

## procedureName()

@return the procedure

## cron()

@return the cron expression

## interval()

@return the fixed interval

## args()

@return the procedure's arguments

## timeout()

@return the run's timeout

## description()

@return the description

## enabled()

@return whether it is enabled

## ifVersion()

@return the required current version
