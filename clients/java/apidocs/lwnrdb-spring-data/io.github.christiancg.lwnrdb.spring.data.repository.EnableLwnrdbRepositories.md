Turns on LWNRDB repositories outside Spring Boot (the starter does it for you).

## value()

@return alias of `basePackages`

## basePackages()

@return packages to scan for repositories

## basePackageClasses()

@return classes whose packages are scanned

## includeFilters()

@return filters selecting repositories

## excludeFilters()

@return filters excluding repositories

## repositoryImplementationPostfix()

@return the suffix of custom implementation classes

## namedQueriesLocation()

@return unused: LWNRDB has no named queries

## queryLookupStrategy()

@return how queries are found; only derived queries exist

## repositoryFactoryBeanClass()

@return the factory bean class

## repositoryBaseClass()

@return the base class of repositories

## nameGenerator()

@return the bean name generator

## considerNestedRepositories()

@return whether nested interfaces are repositories

## bootstrapMode()

@return when repositories are created

## templateRef()

@return the name of the `LwnrdbOperations` bean
