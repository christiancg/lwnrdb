The scheduled procedures of one database. Obtained from `Database.schedules()`.

## Schedules(ClientContext, String)

Internal: use `Database.schedules()`.

@param context the client's internal context
@param database the database

## save(ScheduleDefinition)

@param schedule the schedule
@return its new version

## delete(String)

@param name the schedule

## list()

@return the schedules, without their arguments

## list(boolean)

@param includeArgs whether to include each schedule's arguments
@return the schedules
