Administration of the server. Obtained from `LwnrdbClient.Admin()`. Every operation behind it needs an admin user.

## Scripts()
Returns the scripts currently running on the server.

@returns the scripts facade

## Transactions()
Returns the unresolved distributed transactions of a cluster.

@returns the distributed transactions facade

## TriggerRuns()
Returns the trigger runs that wait for a retry or were dead-lettered.

@returns the trigger runs facade

## Users()
Returns the user and permission management.

@returns the users facade
