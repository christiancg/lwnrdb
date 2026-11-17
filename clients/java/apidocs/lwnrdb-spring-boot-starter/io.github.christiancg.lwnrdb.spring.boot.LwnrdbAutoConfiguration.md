Creates the `ClientSettings` and the `LwnrdbClient` (closed with the context) unless the application
defines its own.

## lwnrdbClientSettings(LwnrdbProperties, ObjectProvider, ObjectProvider)

@param properties the `lwnrdb.*` properties
@param objectMapper Boot's Jackson mapper, when there is exactly one
@param customizers applied in order
@return the settings

## lwnrdbClient(ClientSettings)

@param settings the settings
@return the connected client
@throws org.springframework.dao.DataAccessResourceFailureException when the server cannot be reached or refuses the login
