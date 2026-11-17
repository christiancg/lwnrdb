An immutable vector of finite numbers, stored as `#vector(v0,v1,...)`.

Vectors are compared by their components. The values are copied on creation and on `ToArray`, so a vector never changes.

## From(IEnumerable<double>)
Creates a vector from a sequence of doubles.

@param components the components, at least one, all finite
@returns the vector
@exception System.ArgumentException when there is no component or one is not finite

## From(IEnumerable<float>)
Creates a vector from a sequence of floats, widened to double.

@param components the components, at least one, all finite
@returns the vector
@exception System.ArgumentException when there is no component or one is not finite

## Of(double[])
Creates a vector.

@param components the components, at least one, all finite
@returns the vector
@exception System.ArgumentException when there is no component or one is not finite

## ToArray()
Copies the components into a new array.

@returns the components

## Components
The components, as a read-only view.

## Dimensions
The number of components.

## this\[int\]
The component at an index.

@param index the zero-based index
@exception System.IndexOutOfRangeException when the index is out of range
