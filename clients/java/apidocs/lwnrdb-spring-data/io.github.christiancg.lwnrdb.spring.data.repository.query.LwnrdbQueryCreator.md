Turns a derived query method's parts into an LWNRDB filter.

## LwnrdbQueryCreator(PartTree, ParameterAccessor, LwnrdbOperations, Class)

@param tree the parsed method name
@param parameters the call's arguments
@param operations resolves field paths
@param type the entity class

## validate(PartTree, String, LwnrdbOperations, Class)

Refuses, when the repository is created, a method LWNRDB cannot answer.

@param tree the parsed method name
@param methodName the method
@param operations resolves field paths
@param type the entity class
