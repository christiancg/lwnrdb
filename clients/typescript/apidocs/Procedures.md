# Procedures

The stored procedures of one database.

## save

Creates or replaces a procedure.

@param definition the procedure
@returns its new version

## delete

@param name the procedure to delete

## list

@param includeSource whether to include each source
@returns the procedures

## call

@param name the procedure
@param args optional arguments
@returns its value, logs and run id
