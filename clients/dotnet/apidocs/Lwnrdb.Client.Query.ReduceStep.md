Folds the rows into one value with a script.

@param Script the script source
@param HasInitialValue whether an initial value was given
@param InitialValue the accumulator's initial value as JSON
@param ResultField the field that receives the result, or null for the server's default

## ReduceStep(string, bool, JsonNode, string)
Creates a step.

@param Script the script source
@param HasInitialValue whether an initial value was given
@param InitialValue the accumulator's initial value as JSON
@param ResultField the field that receives the result, or null

## HasInitialValue
Whether an initial value was given. It distinguishes no value from an initial JSON null.

## InitialValue
The accumulator's initial value as JSON.

## ResultField
The field that receives the result, or null for the server's default.

## Script
The script source.
