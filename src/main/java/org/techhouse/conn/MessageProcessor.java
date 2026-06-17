package org.techhouse.conn;

import java.io.*;
import java.net.Socket;
import java.util.UUID;
import org.techhouse.cache.Cache;
import org.techhouse.ejson.EJson;
import org.techhouse.ex.InvalidCommandException;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.auth.AuthorizationChecker;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.req.validations.RequestValidator;
import org.techhouse.ops.resp.OperationResponse;

public class MessageProcessor implements Runnable {
    private final EJson eJson = IocContainer.get(EJson.class);
    private final OperationProcessor operationProcessor = IocContainer.get(OperationProcessor.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
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
                var close = false;
                while (!close) {
                    final var message = reader.readLine();
                    if (message == null) {
                        clientTracker.removeById(clientId);
                        break;
                    }
                    if (!message.isBlank()) {
                        var response = "";
                        try {
                            final var parsedMessage = RequestParser.parseRequest(message);
                            final var validationResult = RequestValidator.validate(parsedMessage);
                            if (!validationResult.isValid()) {
                                response = eJson.toJson(new OperationResponse(parsedMessage.getType(),
                                        OperationStatus.ERROR, validationResult.getErrorMessage()));
                            } else {
                                final var type = parsedMessage.getType();
                                final var isPublicOperation = type == OperationType.AUTHENTICATE
                                        || type == OperationType.LIST_DATABASES
                                        || type == OperationType.CLOSE_CONNECTION;

                                if (isPublicOperation) {
                                    final var responseObj = operationProcessor.processMessage(parsedMessage, clientId);
                                    if (responseObj.getType() == OperationType.CLOSE_CONNECTION) {
                                        close = true;
                                        clientTracker.removeById(clientId);
                                    }
                                    response = eJson.toJson(responseObj);
                                } else {
                                    final var username = clientTracker.getAuthenticatedUsername(clientId);
                                    if (username == null) {
                                        response = eJson.toJson(new OperationResponse(type,
                                                OperationStatus.UNAUTHENTICATED, "Must authenticate first"));
                                    } else {
                                        final var user = cache.getAdminUserEntry(username);
                                        if (user == null) {
                                            response = eJson.toJson(new OperationResponse(type,
                                                    OperationStatus.UNAUTHENTICATED, "User no longer exists"));
                                        } else {
                                            final var authzResult = AuthorizationChecker.check(parsedMessage, user);
                                            if (!authzResult.isAllowed()) {
                                                response = eJson.toJson(new OperationResponse(type,
                                                        OperationStatus.FORBIDDEN, authzResult.getReason()));
                                            } else {
                                                final var responseObj = operationProcessor.processMessage(parsedMessage,
                                                        clientId);
                                                if (responseObj.getType() == OperationType.CLOSE_CONNECTION) {
                                                    close = true;
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
                        writer.write(response);
                        writer.newLine();
                        writer.flush();
                    }
                }
            } else {
                final var responseObj = new OperationResponse(OperationType.CLOSE_CONNECTION, OperationStatus.ERROR,
                        "Max number of connections reached");
                writer.write(eJson.toJson(responseObj));
                writer.newLine();
                writer.flush();
            }
        } catch (IOException e) {
            if (clientId != null) {
                clientTracker.removeById(clientId);
            }
            logger.error("General error in MessageProcessor", e);
        }
    }
}
