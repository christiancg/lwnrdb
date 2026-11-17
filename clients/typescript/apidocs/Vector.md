# Vector

A vector of finite numbers, the server's `#vector(...)` value. Immutable; it copies what it is built
from.

## of

@param components at least one finite number
@returns the vector

## from

@param components an array or typed array of at least one finite number
@returns the vector

## dimensions

The number of components.

## component

@param index a component's position
@returns the component

## toArray

@returns a copy of the components

## equals

@param other any value
@returns whether it is a `Vector` with the same components, `-0` and `0` told apart

## toString

@returns a readable form of the vector
