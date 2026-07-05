package org.techhouse.conn;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.UUID;
import javax.net.ssl.SSLException;
import org.techhouse.cache.Cache;
import org.techhouse.ejson.EJson;
import org.techhouse.ex.InvalidCommandException;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.ListenManager;
import org.techhouse.log.Logger;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.auth.AuthorizationChecker;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.req.validations.RequestValidator;
import org.techhouse.ops.resp.AggregateAnalyzeResponse;
import org.techhouse.ops.resp.OperationResponse;

public class MessageProcessor implements Runnable {
    private final EJson eJson = IocContainer.get(EJson.class);
    private final OperationProcessor operationProcessor = IocContainer.get(OperationProcessor.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final ListenManager listenManager = IocContainer.get(ListenManager.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final Logger logger = Logger.logFor(MessageProcessor.class);
    private final Socket socket;
    private final UUID clientId;

    public MessageProcessor(Socket socket) {
        this.socket = socket;
        this.clientId = clientTracker.addClient(socket);
    }

    @Override
    public void run() {
        try (socket) {
            final var reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            final var writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            if (clientId != null) {
                clientTracker.registerWriter(clientId, writer);
                final var writerLock = clientTracker.getWriterLock(clientId);
                var close = false;
                while (!close) {
                    final var message = reader.readLine();
                    if (message == null) {
                        listenManager.unregisterAllForClient(clientId);
                        clientTracker.removeById(clientId);
                        break;
                    }
                    if (!message.isBlank()) {
                        String response;
                        try {
                            final var parsedMessage = RequestParser.parseRequest(message);
                            final var validationResult = RequestValidator.validate(parsedMessage);
                            if (!validationResult.isValid()) {
                                response = eJson.toJson(new OperationResponse(parsedMessage.getType(),
                                        validationResult.getErrorMessage(), ErrorCode.VALIDATION_ERROR));
                            } else {
                                final var type = parsedMessage.getType();
                                final var isPublicOperation = type == OperationType.AUTHENTICATE
                                        || type == OperationType.LIST_DATABASES
                                        || type == OperationType.CLOSE_CONNECTION;

                                if (isPublicOperation) {
                                    final var responseObj = operationProcessor.processMessage(parsedMessage, clientId);
                                    if (responseObj.getType() == OperationType.CLOSE_CONNECTION) {
                                        close = true;
                                        listenManager.unregisterAllForClient(clientId);
                                        clientTracker.removeById(clientId);
                                    }
                                    response = eJson.toJson(responseObj);
                                } else {
                                    final var username = clientTracker.getAuthenticatedUsername(clientId);
                                    if (username == null) {
                                        response = eJson
                                                .toJson(new OperationResponse(type, ErrorCode.MUST_AUTHENTICATE_FIRST));
                                    } else {
                                        final var user = cache.getAdminUserEntry(username);
                                        if (user == null) {
                                            response = eJson.toJson(
                                                    new OperationResponse(type, ErrorCode.USER_NO_LONGER_EXISTS));
                                        } else {
                                            final var authResult = AuthorizationChecker.check(parsedMessage, user);
                                            if (!authResult.isAllowed()) {
                                                response = eJson
                                                        .toJson(new OperationResponse(type, ErrorCode.NO_PERMISSIONS));
                                            } else {
                                                // The query timer brackets only processing: it starts after
                                                // parsing/validation/authorization and stops right after the
                                                // operation returns. Only AGGREGATE with analyze=true is timed.
                                                final var analyze = parsedMessage instanceof AggregateRequest aggReq
                                                        && aggReq.isAnalyze();
                                                final var analyzeStart = analyze ? System.currentTimeMillis() : 0L;
                                                final var responseObj = operationProcessor.processMessage(parsedMessage,
                                                        clientId);
                                                if (analyze
                                                        && responseObj instanceof AggregateAnalyzeResponse analyzeResp
                                                        && analyzeResp.getAnalyzeResult() != null) {
                                                    final var analyzeEnd = System.currentTimeMillis();
                                                    final var analyzeResult = analyzeResp.getAnalyzeResult();
                                                    analyzeResult.setStartTime(analyzeStart);
                                                    analyzeResult.setEndTime(analyzeEnd);
                                                    analyzeResult.setDurationMillis(analyzeEnd - analyzeStart);
                                                }
                                                if (responseObj.getType() == OperationType.CLOSE_CONNECTION) {
                                                    close = true;
                                                    listenManager.unregisterAllForClient(clientId);
                                                    clientTracker.removeById(clientId);
                                                }
                                                response = eJson.toJson(responseObj);
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (InvalidCommandException exception) {
                            response = exception.getMessage();
                        }
                        clientTracker.updateLastCommandTime(clientId);
                        writerLock.lock();
                        try {
                            writer.write(response);
                            writer.newLine();
                            writer.flush();
                        } finally {
                            writerLock.unlock();
                        }
                    }
                }
            } else {
                final var localWriterLock = new java.util.concurrent.locks.ReentrantLock();
                final var responseObj = new OperationResponse(OperationType.CLOSE_CONNECTION,
                        ErrorCode.MAX_CONNECTIONS_REACHED);
                localWriterLock.lock();
                try {
                    writer.write(eJson.toJson(responseObj));
                    writer.newLine();
                    writer.flush();
                } finally {
                    localWriterLock.unlock();
                }
            }
        } catch (SSLException e) {
            // A plaintext or otherwise incompatible client failed the TLS handshake; drop it quietly.
            if (clientId != null) {
                clientTracker.removeById(clientId);
            }
            logger.warning("Rejected connection: TLS handshake failed (non-TLS or incompatible client)");
        } catch (IOException e) {
            if (clientId != null) {
                listenManager.unregisterAllForClient(clientId);
                clientTracker.removeById(clientId);
            }
            logger.error("General error in MessageProcessor", e);
        }
    }
}
