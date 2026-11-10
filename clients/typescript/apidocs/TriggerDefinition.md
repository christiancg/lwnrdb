# TriggerDefinition

A trigger to save.

## name

Its name.

## procedureName

The procedure it calls.

## events

The writes it fires on, at least one.

## timing

Optional; `after` by default.

## mode

Optional; `document` by default.

## allowCascade

Optional: whether its writes may fire triggers.

## enabled

Optional; enabled by default.

## ifVersion

Optional: save only when the stored version is this one.
