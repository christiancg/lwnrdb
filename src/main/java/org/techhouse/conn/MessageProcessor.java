package org.techhouse.conn;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import javax.net.ssl.SSLException;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.ClusterRouter;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ex.InvalidCommandException;
import org.techhouse.ex.RequestTooLargeException;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.ListenManager;
import org.techhouse.log.Logger;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.auth.AuthorizationChecker;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.OperationRequest;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.req.validations.RequestValidator;
import org.techhouse.ops.resp.AggregateAnalyzeResponse;
import org.techhouse.ops.resp.ListenResponse;
import org.techhouse.ops.resp.OperationResponse;

public class MessageProcessor implements Runnable {
    private static final EJson eJson = IocContainer.get(EJson.class);
    private final OperationProcessor operationProcessor = IocContainer.get(OperationProcessor.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final InFlightRequests inFlightRequests = IocContainer.get(InFlightRequests.class);
    private final ListenManager listenManager = IocContainer.get(ListenManager.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final ClusterRouter clusterRouter = IocContainer.get(ClusterRouter.class);
    private final Logger logger = Logger.logFor(MessageProcessor.class);
    private final Configuration configuration = Configuration.getInstance();
    private final Socket socket;
    private final UUID clientId;
    private final BooleanSupplier shuttingDown;
    private final long acceptedAtNanos = System.nanoTime();
    private boolean everAuthenticated;
    private UUID unreapableTransaction;

    public MessageProcessor(Socket socket) {
        this(socket, () -> false);
    }

    public MessageProcessor(Socket socket, BooleanSupplier shuttingDown) {
        this.socket = socket;
        this.shuttingDown = shuttingDown;
        this.clientId = clientTracker.addClient(socket);
    }

    @Override
    public void run() {
        try (socket) {
            final var writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), Globals.BUFFER_SIZE);
            if (clientId != null) {
                clientTracker.registerWriter(clientId, writer);
                final var writerLock = clientTracker.getWriterLock(clientId);
                final var reader = new BoundedLineReader(socket.getInputStream(), socket::setSoTimeout);
                var close = false;
                while (!close) {
                    final var message = readNextLine(reader, writer, writerLock);
                    if (message == null) {
                        break;
                    }
                    if (!message.isBlank()) {
                        close = serveLine(message, nextLineLimitBytes() / Globals.MIN_BYTES_PER_REQUEST_TOKEN, writer,
                                writerLock);
                    }
                }
            } else {
                refuse(writer, new ReentrantLock(), ErrorCode.MAX_CONNECTIONS_REACHED);
            }
        } catch (SSLException e) {
            logger.warning("Rejected connection: TLS handshake failed (non-TLS or incompatible client)");
        } catch (IOException e) {
            logger.error("General error in MessageProcessor", e);
        } catch (RuntimeException e) {
            logger.error("Unexpected failure in MessageProcessor", e);
            throw e;
        } catch (Throwable t) {
            logger.error("Unexpected failure in MessageProcessor", new Exception(t));
            throw t;
        } finally {
            if (clientId != null) {
                try {
                    TransactionOperationHelper.cleanupOnDisconnect(clientId);
                    listenManager.unregisterAllForClient(clientId);
                } finally {
                    clientTracker.removeById(clientId);
                }
            }
        }
    }

    private String readNextLine(BoundedLineReader reader, BufferedWriter writer, ReentrantLock writerLock)
            throws IOException {
        while (true) {
            if (!everAuthenticated() && authRemainingMillis() <= 0) {
                return closeUnauthenticated(writer, writerLock);
            }
            try {
                return reader.readLine(nextReadBudgetMillis(), nextLineLimitBytes());
            } catch (SocketTimeoutException timeout) {
                if (!everAuthenticated()) {
                    return closeUnauthenticated(writer, writerLock);
                }
                reapIdleTransaction();
            } catch (RequestTooLargeException tooLarge) {
                logger.warning("Closed a connection from " + remoteAddress() + ": " + tooLarge.getMessage());
                refuse(writer, writerLock, ErrorCode.REQUEST_TOO_LARGE);
                return null;
            }
        }
    }

    private String closeUnauthenticated(BufferedWriter writer, ReentrantLock writerLock) {
        logger.warning("Closed a connection from " + remoteAddress() + " that did not authenticate within "
                + configuration.getAuthTimeoutMs() + " ms");
        refuse(writer, writerLock, ErrorCode.AUTHENTICATION_TIMEOUT);
        return null;
    }

    private boolean everAuthenticated() {
        if (!everAuthenticated && clientTracker.getAuthenticatedUsername(clientId) != null) {
            everAuthenticated = true;
        }
        return everAuthenticated;
    }

    private long authRemainingMillis() {
        return configuration.getAuthTimeoutMs() - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acceptedAtNanos);
    }

    private long nextReadBudgetMillis() {
        if (!everAuthenticated()) {
            return authRemainingMillis();
        }
        return hasTimedTransaction() ? configuration.getTransactionIdleTimeoutMs() : 0L;
    }

    private int nextLineLimitBytes() {
        final long ceiling = everAuthenticated() ? Integer.MAX_VALUE : Globals.MAX_UNAUTHENTICATED_REQUEST_BYTES;
        return (int) Math.min(configuration.getMaxRequestSize(), ceiling);
    }

    private boolean hasTimedTransaction() {
        final var transaction = clientTracker.getActiveTransaction(clientId);
        return transaction != null && !transaction.getTransactionId().equals(unreapableTransaction)
                && TransactionOperationHelper.isReapable(clientId);
    }

    private void reapIdleTransaction() {
        if (!hasTimedTransaction()) {
            return;
        }
        final var transactionId = clientTracker.getActiveTransaction(clientId).getTransactionId();
        if (TransactionOperationHelper.reapIdleTransaction(clientId)) {
            clientTracker.markTransactionReaped(clientId);
            logger.info("Rolled back transaction " + transactionId + " of " + remoteAddress() + " after "
                    + configuration.getTransactionIdleTimeoutMs() + " ms without a request");
        } else {
            unreapableTransaction = transactionId;
            logger.warning("Could not roll back idle transaction " + transactionId + " of " + remoteAddress()
                    + "; it stays open and is no longer timed");
        }
    }

    private String remoteAddress() {
        return String.valueOf(socket.getRemoteSocketAddress());
    }

    private void refuse(BufferedWriter writer, ReentrantLock writerLock, ErrorCode code) {
        try {
            writeLine(writer, writerLock, eJson.toJson(new OperationResponse(OperationType.CLOSE_CONNECTION, code)));
        } catch (IOException e) {
            logger.warning("Could not deliver the " + code.getCode() + " refusal: " + e.getMessage());
        }
    }

    private static void writeLine(BufferedWriter writer, ReentrantLock writerLock, String line) throws IOException {
        writerLock.lock();
        try {
            writer.write(line);
            writer.newLine();
            writer.flush();
        } finally {
            writerLock.unlock();
        }
    }

    private boolean serveLine(String message, int maxTokens, BufferedWriter writer, ReentrantLock writerLock)
            throws IOException {
        var close = false;
        inFlightRequests.enter();
        try {
            String response;
            UUID deliveredListen = null;
            OperationType requestType = null;
            try {
                final var parsedMessage = RequestParser.parseRequest(message, maxTokens);
                requestType = parsedMessage.getType();
                final var validationResult = RequestValidator.validate(parsedMessage);
                if (!validationResult.isValid()) {
                    response = eJson.toJson(new OperationResponse(parsedMessage.getType(),
                            validationResult.getErrorMessage(), validationResult.getErrorCode()));
                } else if (isRefusedDuringShutdown(parsedMessage.getType())) {
                    response = eJson
                            .toJson(new OperationResponse(parsedMessage.getType(), ErrorCode.SERVER_SHUTTING_DOWN));
                } else if (isFirstRequestAfterReap(parsedMessage.getType())) {
                    response = eJson
                            .toJson(new OperationResponse(parsedMessage.getType(), ErrorCode.TRANSACTION_IDLE_TIMEOUT));
                } else {
                    final var type = parsedMessage.getType();
                    final var isPublicOperation = type == OperationType.AUTHENTICATE
                            || type == OperationType.CLOSE_CONNECTION;

                    if (isPublicOperation) {
                        final var responseObj = operationProcessor.processMessage(parsedMessage, clientId);
                        if (closesConnection(responseObj)) {
                            close = true;
                        }
                        response = eJson.toJson(responseObj);
                    } else if (isOwnTransactionRollback(type, clientId)) {
                        final var handled = handleAuthorized(parsedMessage, message, clientId);
                        response = handled.response();
                        deliveredListen = handled.deliveredListen();
                    } else {
                        final var username = clientTracker.getAuthenticatedUsername(clientId);
                        if (username == null) {
                            response = eJson.toJson(new OperationResponse(type, ErrorCode.MUST_AUTHENTICATE_FIRST));
                        } else {
                            final var user = cache.getAdminUserEntry(username);
                            if (user == null) {
                                response = eJson.toJson(new OperationResponse(type, ErrorCode.USER_NO_LONGER_EXISTS));
                            } else {
                                final var authResult = AuthorizationChecker.check(parsedMessage, user);
                                if (!authResult.isAllowed()) {
                                    response = eJson.toJson(new OperationResponse(type, ErrorCode.NO_PERMISSIONS));
                                } else {
                                    final var handled = handleAuthorized(parsedMessage, message, clientId);
                                    response = handled.response();
                                    deliveredListen = handled.deliveredListen();
                                    if (handled.close()) {
                                        close = true;
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (InvalidCommandException exception) {
                logger.warning("Refused an unparseable request: " + causeMessageOf(exception));
                response = exception.getMessage();
            } catch (RuntimeException exception) {
                logger.error("Request handling failed unexpectedly", exception);
                response = eJson.toJson(new OperationResponse(requestType, ErrorCode.ERROR_RETRIEVING));
            } catch (StackOverflowError error) {
                logger.error("Request handling overflowed the stack", error);
                response = eJson.toJson(new OperationResponse(requestType, ErrorCode.ERROR_RETRIEVING));
            } catch (OutOfMemoryError error) {
                logger.error("Request handling ran out of memory, closing the connection", error);
                response = eJson.toJson(new OperationResponse(requestType, ErrorCode.ERROR_RETRIEVING));
                close = true;
            }
            clientTracker.updateLastCommandTime(clientId);
            writeLine(writer, writerLock, response);
            if (deliveredListen != null) {
                listenManager.markDelivered(deliveredListen);
            }
        } finally {
            inFlightRequests.exit();
        }
        return close;
    }

    private boolean isFirstRequestAfterReap(OperationType type) {
        return type != OperationType.CLOSE_CONNECTION && clientTracker.consumeTransactionReaped(clientId);
    }

    private record Handled(String response, boolean close, UUID deliveredListen) {
    }

    private static boolean closesConnection(OperationResponse responseObj) {
        return responseObj.getType() == OperationType.CLOSE_CONNECTION
                || ErrorCode.TOO_MANY_FAILED_ATTEMPTS.getCode().equals(responseObj.getErrorCode());
    }

    private boolean isRefusedDuringShutdown(OperationType type) {
        return shuttingDown.getAsBoolean() && type != OperationType.ROLLBACK_TRANSACTION
                && type != OperationType.CLOSE_CONNECTION;
    }

    private static String causeMessageOf(InvalidCommandException exception) {
        final var cause = exception.getCause();
        return cause == null ? exception.getMessage() : cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    private static String withoutTriggerDepth(String rawMessage) {
        if (rawMessage == null || !rawMessage.contains(Globals.TRIGGER_DEPTH_FIELD)) {
            return rawMessage;
        }
        try {
            final var root = eJson.fromJson(rawMessage, JsonObject.class);
            root.remove(Globals.TRIGGER_DEPTH_FIELD);
            return eJson.toJson(root);
        } catch (Exception e) {
            return rawMessage;
        }
    }

    private boolean isOwnTransactionRollback(OperationType type, UUID clientId) {
        return type == OperationType.ROLLBACK_TRANSACTION && clientTracker.getActiveTransaction(clientId) != null;
    }

    private Handled handleAuthorized(OperationRequest parsedMessage, String rawMessage, UUID clientId) {
        // A cascade depth that arrived on the wire is never trusted: only a running trigger may set one.
        parsedMessage.setTriggerDepth(0);
        final var schemaError = org.techhouse.ops.SchemaValidationHelper.check(parsedMessage);
        if (schemaError != null) {
            return new Handled(eJson.toJson(schemaError), false, null);
        }
        final var forwarded = clusterRouter.forward(parsedMessage, withoutTriggerDepth(rawMessage),
                clientTracker.getActiveTransaction(clientId) != null, clientTracker.getAuthenticatedUsername(clientId),
                clientId);
        if (forwarded != null) {
            return new Handled(forwarded, false, null);
        }
        final var analyze = parsedMessage instanceof AggregateRequest aggReq && aggReq.isAnalyze();
        final var analyzeStart = analyze ? System.currentTimeMillis() : 0L;
        final var responseObj = operationProcessor.processMessage(parsedMessage, clientId);
        if (analyze && responseObj instanceof AggregateAnalyzeResponse analyzeResp
                && analyzeResp.getAnalyzeResult() != null) {
            final var analyzeEnd = System.currentTimeMillis();
            final var analyzeResult = analyzeResp.getAnalyzeResult();
            analyzeResult.setStartTime(analyzeStart);
            analyzeResult.setEndTime(analyzeEnd);
            analyzeResult.setDurationMillis(analyzeEnd - analyzeStart);
        }
        return new Handled(eJson.toJson(responseObj), responseObj.getType() == OperationType.CLOSE_CONNECTION,
                listenToDeliver(responseObj));
    }

    private static UUID listenToDeliver(OperationResponse responseObj) {
        if (responseObj instanceof ListenResponse listen && !listen.isUpdate() && listen.getListenId() != null) {
            return UUID.fromString(listen.getListenId());
        }
        return null;
    }
}
