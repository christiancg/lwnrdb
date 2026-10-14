package org.techhouse.conn;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.ClusterRouter;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.Client;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.internal.Utf8Encoder;
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
    private static final long FINAL_FRAMES_WAIT_MS = 1000L;
    private final OperationProcessor operationProcessor = IocContainer.get(OperationProcessor.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final PreAuthSlots preAuthSlots = IocContainer.get(PreAuthSlots.class);
    private final InFlightRequests inFlightRequests = IocContainer.get(InFlightRequests.class);
    private final ListenManager listenManager = IocContainer.get(ListenManager.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final ClusterRouter clusterRouter = IocContainer.get(ClusterRouter.class);
    private final Logger logger = Logger.logFor(MessageProcessor.class);
    private final Configuration configuration = Configuration.getInstance();
    private final Socket socket;
    private final UUID clientId;
    private final String remoteHost;
    private final boolean preAuthAdmitted;
    private final BooleanSupplier shuttingDown;
    private final long acceptedAtNanos = System.nanoTime();
    private boolean everAuthenticated;
    private boolean holdingPreAuthSlot;
    private volatile boolean serving;
    private int unauthenticatedInvalidRequests;
    private HandshakeDeadline.Armed deadline;
    private UUID unreapableTransaction;
    private OutboundQueue outbound;
    private ReentrantLock writerLock;

    public MessageProcessor(Socket socket) {
        this(socket, () -> false);
    }

    public MessageProcessor(Socket socket, BooleanSupplier shuttingDown) {
        this.socket = socket;
        this.shuttingDown = shuttingDown;
        this.clientId = clientTracker.addClient(socket);
        this.remoteHost = hostOf(socket);
        this.preAuthAdmitted = clientId != null && preAuthSlots.acquire(remoteHost);
        this.holdingPreAuthSlot = preAuthAdmitted;
    }

    private static String hostOf(Socket socket) {
        final var address = socket.getInetAddress();
        return address == null ? "unknown" : address.getHostAddress();
    }

    @Override
    public void run() {
        deadline = HandshakeDeadline.arm(socket, configuration.getAuthTimeoutMs() + Globals.HANDSHAKE_DEADLINE_GRACE_MS,
                this::isStillConnecting);
        try (socket) {
            if (refusedBeforeTlsHandshake()) {
                return;
            }
            completeTlsHandshake();
            final var out = new BufferedOutputStream(socket.getOutputStream(), Globals.BUFFER_SIZE);
            if (clientId != null && !preAuthAdmitted) {
                ConnectionLog.warn(logger, "Refused a connection from " + remoteAddress() + ": " + remoteHost
                        + " already holds the maximum number of unauthenticated connections");
                OutboundQueue.sendDirectly(out, ErrorCode.TOO_MANY_UNAUTHENTICATED_CONNECTIONS);
            } else if (clientId != null) {
                serveConnection(out);
            } else {
                OutboundQueue.sendDirectly(out, ErrorCode.MAX_CONNECTIONS_REACHED);
            }
        } catch (SSLException e) {
            ConnectionLog.warn(logger, "Rejected connection: TLS handshake failed (non-TLS or incompatible client)");
        } catch (IOException e) {
            reportDroppedConnection(e);
        } catch (RuntimeException e) {
            logger.error("Unexpected failure in MessageProcessor", e);
            throw e;
        } catch (Throwable t) {
            logger.error("Unexpected failure in MessageProcessor", new Exception(t));
            throw t;
        } finally {
            deadline.disarm();
            releasePreAuthSlot();
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

    private void serveConnection(OutputStream out) throws IOException {
        outbound = new OutboundQueue(out, socket, configuration.sizeValue(ConfigKey.CLIENT_OUTBOUND_QUEUE_MAX_BYTES),
                OutboundQueue.VIRTUAL_DRAINER, this::outboundOverflowed);
        clientTracker.registerOutbound(clientId, outbound);
        writerLock = clientTracker.getWriterLock(clientId);
        final var reader = new BoundedLineReader(socket.getInputStream(), socket::setSoTimeout);
        var close = false;
        while (!close && outbound.isOpen()) {
            final var message = readNextLine(reader);
            if (message == null || !outbound.isOpen()) {
                break;
            }
            if (!message.isBlank()) {
                close = serveLine(message, nextLineLimitBytes() / Globals.MIN_BYTES_PER_REQUEST_TOKEN);
            }
        }
        outbound.awaitDrained(FINAL_FRAMES_WAIT_MS);
    }

    private void outboundOverflowed() {
        listenManager.unregisterAllForClient(clientId);
        logger.warning("Closing the connection from " + remoteAddress() + ": its unread responses exceeded "
                + ConfigKey.CLIENT_OUTBOUND_QUEUE_MAX_BYTES.key() + " (" + ErrorCode.OUTBOUND_BACKLOG.getCode() + ")");
    }

    private void reportDroppedConnection(IOException e) {
        if (outbound != null && !outbound.isOpen()) {
            return;
        }
        if (clientId != null && everAuthenticated()) {
            logger.error("General error in MessageProcessor", e);
        } else if (!deadline.hasFired()) {
            ConnectionLog.warn(logger, "Dropped an unauthenticated connection from " + remoteAddress() + ": "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private boolean refusedBeforeTlsHandshake() {
        if (!(socket instanceof SSLSocket) || clientId != null && preAuthAdmitted) {
            return false;
        }
        ConnectionLog.warn(logger,
                "Closed a TLS connection from " + remoteAddress() + " before its handshake: "
                        + (clientId == null
                                ? "the server already holds maxConnections connections"
                                : remoteHost + " already holds the maximum number of unauthenticated connections"));
        return true;
    }

    private void completeTlsHandshake() throws IOException {
        if (socket instanceof SSLSocket sslSocket) {
            sslSocket.setSoTimeout((int) Math.clamp(authRemainingMillis(), 1L, Integer.MAX_VALUE));
            sslSocket.startHandshake();
        }
    }

    private boolean isStillConnecting() {
        return !serving && (clientId == null || clientTracker.getAuthenticatedUsername(clientId) == null);
    }

    private void releasePreAuthSlot() {
        if (holdingPreAuthSlot) {
            holdingPreAuthSlot = false;
            preAuthSlots.release(remoteHost);
        }
    }

    private String readNextLine(BoundedLineReader reader) throws IOException {
        while (true) {
            if (!everAuthenticated() && authRemainingMillis() <= 0) {
                return closeUnauthenticated();
            }
            if (transactionAgeRemainingMillis() <= 0) {
                reapTransaction(Client.TransactionReap.MAX_AGE);
            }
            final var budget = nextReadBudget();
            try {
                return reader.readLine(budget.millis(), nextLineLimitBytes());
            } catch (SocketTimeoutException timeout) {
                if (!everAuthenticated()) {
                    return closeUnauthenticated();
                }
                reapTransaction(budget.reapReason());
            } catch (RequestTooLargeException tooLarge) {
                ConnectionLog.warn(logger,
                        "Closed a connection from " + remoteAddress() + ": " + tooLarge.getMessage());
                enqueue(OutboundQueue.closeFrame(ErrorCode.REQUEST_TOO_LARGE));
                return null;
            }
        }
    }

    private String closeUnauthenticated() {
        ConnectionLog.warn(logger, "Closed a connection from " + remoteAddress() + " that did not authenticate within "
                + configuration.getAuthTimeoutMs() + " ms");
        enqueue(OutboundQueue.closeFrame(ErrorCode.AUTHENTICATION_TIMEOUT));
        return null;
    }

    private boolean everAuthenticated() {
        if (!everAuthenticated && clientTracker.getAuthenticatedUsername(clientId) != null) {
            everAuthenticated = true;
            deadline.disarm();
            releasePreAuthSlot();
        }
        return everAuthenticated;
    }

    private long authRemainingMillis() {
        return configuration.getAuthTimeoutMs() - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acceptedAtNanos);
    }

    private record ReadBudget(long millis, Client.TransactionReap reapReason) {
    }

    private ReadBudget nextReadBudget() {
        if (!everAuthenticated()) {
            return new ReadBudget(authRemainingMillis(), Client.TransactionReap.IDLE);
        }
        if (lacksTimedTransaction()) {
            return new ReadBudget(0L, Client.TransactionReap.IDLE);
        }
        final var ageRemaining = transactionAgeRemainingMillis();
        final var idleTimeout = configuration.getTransactionIdleTimeoutMs();
        return new ReadBudget(Math.clamp(ageRemaining, 1L, idleTimeout),
                ageRemaining < idleTimeout ? Client.TransactionReap.MAX_AGE : Client.TransactionReap.IDLE);
    }

    private long transactionAgeRemainingMillis() {
        final var maxAge = configuration.longValue(ConfigKey.TRANSACTION_MAX_AGE_MS);
        if (maxAge == 0 || lacksTimedTransaction()) {
            return Long.MAX_VALUE;
        }
        return maxAge - clientTracker.getActiveTransaction(clientId).ageMillis();
    }

    private int nextLineLimitBytes() {
        final long ceiling = everAuthenticated() ? Integer.MAX_VALUE : Globals.MAX_UNAUTHENTICATED_REQUEST_BYTES;
        return (int) Math.min(configuration.getMaxRequestSize(), ceiling);
    }

    private boolean lacksTimedTransaction() {
        final var transaction = clientTracker.getActiveTransaction(clientId);
        return transaction == null || transaction.getTransactionId().equals(unreapableTransaction)
                || !TransactionOperationHelper.isReapable(clientId);
    }

    private void reapTransaction(Client.TransactionReap reason) {
        if (lacksTimedTransaction()) {
            return;
        }
        final var transactionId = clientTracker.getActiveTransaction(clientId).getTransactionId();
        if (TransactionOperationHelper.reapIdleTransaction(clientId)) {
            clientTracker.markTransactionReaped(clientId, reason);
            logger.info("Rolled back transaction " + transactionId + " of " + remoteAddress() + " after "
                    + (reason == Client.TransactionReap.MAX_AGE
                            ? "reaching transactionMaxAgeMs"
                            : configuration.getTransactionIdleTimeoutMs() + " ms without a request"));
        } else {
            unreapableTransaction = transactionId;
            logger.warning("Could not roll back idle transaction " + transactionId + " of " + remoteAddress()
                    + "; it stays open and is no longer timed");
        }
    }

    private String remoteAddress() {
        return String.valueOf(socket.getRemoteSocketAddress());
    }

    private void enqueue(byte[] frame) {
        writerLock.lock();
        try {
            outbound.offer(frame);
        } finally {
            writerLock.unlock();
        }
    }

    private boolean serveLine(String message, int maxTokens) {
        var close = false;
        serving = true;
        inFlightRequests.enter();
        try {
            byte[] response;
            UUID deliveredListen = null;
            OperationType requestType = null;
            try {
                final var parsedMessage = RequestParser.parseRequest(message, maxTokens);
                requestType = parsedMessage.getType();
                final var handled = dispatch(parsedMessage, message);
                response = handled.response();
                deliveredListen = handled.deliveredListen();
                close = handled.close();
            } catch (InvalidCommandException exception) {
                ConnectionLog.warn(logger,
                        "Refused an unparseable request from " + remoteAddress() + ": " + causeMessageOf(exception));
                response = Utf8Encoder.encode(exception.getMessage());
            } catch (RuntimeException exception) {
                logger.error("Request handling failed unexpectedly", exception);
                response = OutboundQueue.frameOf(new OperationResponse(requestType, ErrorCode.ERROR_RETRIEVING));
            } catch (StackOverflowError error) {
                logger.error("Request handling overflowed the stack", error);
                response = OutboundQueue.frameOf(new OperationResponse(requestType, ErrorCode.ERROR_RETRIEVING));
            } catch (OutOfMemoryError error) {
                logger.error("Request handling ran out of memory, closing the connection", error);
                response = OutboundQueue.frameOf(new OperationResponse(requestType, ErrorCode.ERROR_RETRIEVING));
                close = true;
            }
            clientTracker.updateLastCommandTime(clientId);
            close = close || exhaustedUnauthenticatedInvalidRequests();
            enqueue(response);
            if (deliveredListen != null) {
                listenManager.markDelivered(deliveredListen);
            }
        } finally {
            inFlightRequests.exit();
            serving = false;
        }
        return close;
    }

    private boolean exhaustedUnauthenticatedInvalidRequests() {
        if (everAuthenticated()) {
            return false;
        }
        final var limit = configuration.intValue(ConfigKey.MAX_UNAUTHENTICATED_INVALID_REQUESTS);
        unauthenticatedInvalidRequests++;
        return limit != 0 && unauthenticatedInvalidRequests >= limit;
    }

    private Handled dispatch(OperationRequest parsedMessage, String rawMessage) {
        final var type = parsedMessage.getType();
        if (isRefusedDuringShutdown(type)) {
            return refusal(type, ErrorCode.SERVER_SHUTTING_DOWN);
        }
        if (type == OperationType.AUTHENTICATE || type == OperationType.CLOSE_CONNECTION) {
            return admitted(parsedMessage, () -> publicOperation(parsedMessage));
        }
        if (isOwnTransactionRollback(type, clientId)) {
            return admitted(parsedMessage, () -> handleAuthorized(parsedMessage, rawMessage, clientId));
        }
        final var username = clientTracker.getAuthenticatedUsername(clientId);
        if (username == null) {
            return refusal(type, ErrorCode.MUST_AUTHENTICATE_FIRST);
        }
        return admitted(parsedMessage, () -> authorized(parsedMessage, rawMessage, username));
    }

    private Handled admitted(OperationRequest parsedMessage, Supplier<Handled> next) {
        final var type = parsedMessage.getType();
        final var validationResult = RequestValidator.validate(parsedMessage);
        if (!validationResult.isValid()) {
            return new Handled(OutboundQueue.frameOf(
                    new OperationResponse(type, validationResult.getErrorMessage(), validationResult.getErrorCode())),
                    false, null);
        }
        final var reaped = firstRequestAfterReap(type);
        if (reaped != null) {
            return refusal(type, reaped);
        }
        return next.get();
    }

    private Handled publicOperation(OperationRequest parsedMessage) {
        final var responseObj = operationProcessor.processMessage(parsedMessage, clientId);
        return new Handled(OutboundQueue.frameOf(responseObj), closesConnection(responseObj), null);
    }

    private Handled authorized(OperationRequest parsedMessage, String rawMessage, String username) {
        final var type = parsedMessage.getType();
        final var user = cache.getAdminUserEntry(username);
        if (user == null) {
            return refusal(type, ErrorCode.USER_NO_LONGER_EXISTS);
        }
        final var authorization = AuthorizationChecker.check(parsedMessage, user);
        if (!authorization.isAllowed()) {
            return refusal(type, authorization.getErrorCode());
        }
        return handleAuthorized(parsedMessage, rawMessage, clientId);
    }

    private static Handled refusal(OperationType type, ErrorCode code) {
        return new Handled(OutboundQueue.frameOf(new OperationResponse(type, code)), false, null);
    }

    private ErrorCode firstRequestAfterReap(OperationType type) {
        if (type == OperationType.CLOSE_CONNECTION) {
            return null;
        }
        final var reaped = clientTracker.consumeTransactionReaped(clientId);
        if (reaped == null) {
            return null;
        }
        return reaped == Client.TransactionReap.MAX_AGE
                ? ErrorCode.TRANSACTION_TOO_OLD
                : ErrorCode.TRANSACTION_IDLE_TIMEOUT;
    }

    private record Handled(byte[] response, boolean close, UUID deliveredListen) {
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

    private static String withoutTriggerDepth(String rawMessage, int wireDepth) {
        if (rawMessage == null || wireDepth == 0) {
            return rawMessage;
        }
        final var root = eJson.fromJson(rawMessage, JsonObject.class);
        root.remove(Globals.TRIGGER_DEPTH_FIELD);
        return eJson.toJson(root);
    }

    private boolean isOwnTransactionRollback(OperationType type, UUID clientId) {
        return type == OperationType.ROLLBACK_TRANSACTION && clientTracker.getActiveTransaction(clientId) != null;
    }

    private Handled handleAuthorized(OperationRequest parsedMessage, String rawMessage, UUID clientId) {
        final var wireDepth = parsedMessage.getTriggerDepth();
        // A cascade depth that arrived on the wire is never trusted: only a running trigger may set one.
        parsedMessage.setTriggerDepth(0);
        final var schemaError = org.techhouse.ops.SchemaValidationHelper.check(parsedMessage);
        if (schemaError != null) {
            return new Handled(OutboundQueue.frameOf(schemaError), false, null);
        }
        final var forwarded = clusterRouter.forward(parsedMessage, withoutTriggerDepth(rawMessage, wireDepth),
                clientTracker.getActiveTransaction(clientId) != null, clientTracker.getAuthenticatedUsername(clientId),
                clientId);
        if (forwarded != null) {
            return new Handled(Utf8Encoder.encode(forwarded), false, null);
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
        return new Handled(OutboundQueue.frameOf(responseObj), responseObj.getType() == OperationType.CLOSE_CONNECTION,
                listenToDeliver(responseObj));
    }

    private static UUID listenToDeliver(OperationResponse responseObj) {
        if (responseObj instanceof ListenResponse listen && !listen.isUpdate() && listen.getListenId() != null) {
            return UUID.fromString(listen.getListenId());
        }
        return null;
    }
}
