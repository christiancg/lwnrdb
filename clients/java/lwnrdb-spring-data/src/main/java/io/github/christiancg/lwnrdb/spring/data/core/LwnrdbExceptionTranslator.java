package io.github.christiancg.lwnrdb.spring.data.core;

import io.github.christiancg.lwnrdb.client.error.AuthenticationException;
import io.github.christiancg.lwnrdb.client.error.ConflictException;
import io.github.christiancg.lwnrdb.client.error.ConnectionLostException;
import io.github.christiancg.lwnrdb.client.error.ForbiddenException;
import io.github.christiancg.lwnrdb.client.error.LwnrdbException;
import io.github.christiancg.lwnrdb.client.error.MappingException;
import io.github.christiancg.lwnrdb.client.error.NotFoundException;
import io.github.christiancg.lwnrdb.client.error.PoolExhaustedException;
import io.github.christiancg.lwnrdb.client.error.RequestTimeoutException;
import io.github.christiancg.lwnrdb.client.error.ServerClosedConnectionException;
import io.github.christiancg.lwnrdb.client.error.UnavailableException;
import io.github.christiancg.lwnrdb.client.error.UnknownOutcomeException;
import io.github.christiancg.lwnrdb.client.protocol.ErrorCode;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.PermissionDeniedDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.dao.support.PersistenceExceptionTranslator;

public class LwnrdbExceptionTranslator implements PersistenceExceptionTranslator {
    private static final String CLIENT_ERROR_RANGE = "400";

    @Override
    public @Nullable DataAccessException translateExceptionIfPossible(RuntimeException exception) {
        if (exception instanceof DataAccessException alreadyTranslated) {
            return alreadyTranslated;
        }
        if (exception instanceof IllegalArgumentException) {
            return new InvalidDataAccessApiUsageException(exception.getMessage(), exception);
        }
        if (exception instanceof LwnrdbException lwnrdb) {
            return translate(lwnrdb);
        }
        return null;
    }

    public DataAccessException translate(LwnrdbException exception) {
        final var message = exception.getMessage();
        if (exception instanceof RequestTimeoutException) {
            return new QueryTimeoutException(message, exception);
        }
        if (exception instanceof ConnectionLostException || exception instanceof ServerClosedConnectionException
                || exception instanceof PoolExhaustedException || exception instanceof AuthenticationException) {
            return new DataAccessResourceFailureException(message, exception);
        }
        if (exception instanceof ForbiddenException) {
            return new PermissionDeniedDataAccessException(message, exception);
        }
        if (exception instanceof NotFoundException || exception instanceof MappingException) {
            return new DataRetrievalFailureException(message, exception);
        }
        if (exception instanceof ConflictException) {
            return conflict(exception);
        }
        if (exception instanceof UnavailableException) {
            return new TransientDataAccessResourceException(message, exception);
        }
        if (exception instanceof UnknownOutcomeException) {
            return new UncategorizedLwnrdbException(message, exception);
        }
        return clientError(exception);
    }

    private static DataAccessException conflict(LwnrdbException exception) {
        if (exception.hasCode(ErrorCode.DOCUMENT_ALREADY_EXISTS)) {
            return new DuplicateKeyException(exception.getMessage(), exception);
        }
        if (exception.hasCode(ErrorCode.TRANSACTION_LOCK_TIMEOUT)) {
            return new PessimisticLockingFailureException(exception.getMessage(), exception);
        }
        if (exception.hasCode(ErrorCode.TRANSACTION_IDLE_TIMEOUT)) {
            return new TransientDataAccessResourceException(exception.getMessage(), exception);
        }
        return new ConcurrencyFailureException(exception.getMessage(), exception);
    }

    private static DataAccessException clientError(LwnrdbException exception) {
        if (exception.hasCode(ErrorCode.DUPLICATE_ID)) {
            return new DuplicateKeyException(exception.getMessage(), exception);
        }
        if (exception.hasCode(ErrorCode.SCHEMA_VALIDATION_FAILED)
                || exception.hasCode(ErrorCode.BEFORE_HOOK_REJECTED)) {
            return new DataIntegrityViolationException(exception.getMessage(), exception);
        }
        if (exception.rawCode().filter(code -> code.startsWith(CLIENT_ERROR_RANGE)).isPresent()) {
            return new InvalidDataAccessApiUsageException(exception.getMessage(), exception);
        }
        return new UncategorizedLwnrdbException(exception.getMessage(), exception);
    }
}
