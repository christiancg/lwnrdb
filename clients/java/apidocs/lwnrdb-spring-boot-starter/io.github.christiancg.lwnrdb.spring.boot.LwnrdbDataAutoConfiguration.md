Creates the mapping context (scanning the application's packages for `@LwnrdbEntity`), the
`LwnrdbTemplate` and the `LwnrdbTransactionManager`, and turns on `@Transactional`.

## TEMPLATE_BEAN

The template bean name, `lwnrdbTemplate`.

## TRANSACTION_MANAGER_BEAN

The transaction manager bean name, `lwnrdbTransactionManager`.

## lwnrdbMappingContext(LwnrdbProperties, BeanFactory)

@param properties supplies the default database
@param beanFactory supplies the packages to scan
@return the mapping context

## lwnrdbTemplate(LwnrdbClient, LwnrdbMappingContext)

@param client the client
@param mappingContext the mapping context
@return the template

## lwnrdbTransactionManager(LwnrdbClient)

@param client the client
@return the transaction manager
