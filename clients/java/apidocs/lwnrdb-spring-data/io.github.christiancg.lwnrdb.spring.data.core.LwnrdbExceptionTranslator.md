Turns client exceptions into Spring's `DataAccessException` hierarchy: `400-3` is a
`DuplicateKeyException`, schema and before-hook refusals are `DataIntegrityViolationException`s, lost connections are
`DataAccessResourceFailureException`s, `409-5` is a `PessimisticLockingFailureException`, and so on.

## translate(LwnrdbException)

Translates one client exception; unlike `translateExceptionIfPossible`, it never answers `null`.

@param exception the client's exception
@return the matching `DataAccessException`, `UncategorizedLwnrdbException` when none fits
