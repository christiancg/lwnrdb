A trigger to save: a procedure run after (or before) writes to a collection.

## builder(String, String)

@param name the trigger's name
@param procedureName the procedure it runs
@return a builder

## name()

@return the name

## procedureName()

@return the procedure

## events()

@return the write events it fires on

## timing()

@return before or after the write

## mode()

@return per document or per batch

## allowCascade()

@return whether its own writes fire triggers

## enabled()

@return whether it is enabled

## ifVersion()

@return the required current version
