package org.techhouse.conn;

import org.techhouse.ejson.EJson;
import org.techhouse.ex.InvalidCommandException;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.resp.OperationResponse;

import java.io.*;
import java.net.Socket;
import java.util.UUID;

public class MessageProcessor implements Runnable {
    private final EJson eJson = IocContainer.get(EJson.class);
    private final OperationProcessor operationProcessor = IocContainer.get(OperationProcessor.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final Logger logger = Logger.logFor(MessageProcessor.class);
    private final Socket socket;
    private final UUID clientId;

    public MessageProcessor(Socket socket) {
        this.socket = socket;
        this.clientId = clientTracker.addClient(socket);
    }

    @Override
    public void run() {
        BufferedReader reader;
        BufferedWriter writer;
        try {
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            if (clientId != null) {
                var close = false;
                var message = "";
                while (!close) {
                    message = reader.readLine();
                    if (message != null && !message.isBlank()) {
                        var response = "";
                        try {
                            final var parsedMessage = RequestParser.parseRequest(message);
                            final var responseObj = operationProcessor.processMessage(parsedMessage);
                            if (responseObj.getType() == OperationType.CLOSE_CONNECTION) {
                                close = true;
                                clientTracker.removeById(clientId);
                            }
                            response = eJson.toJson(responseObj);
                        } catch (InvalidCommandException exception) {
                            response = exception.getMessage();
                        }
                        clientTracker.updateLastCommandTime(clientId);
                        writer.write(response);
                        writer.flush();
                    }
                }
            } else {
                final var responseObj = new OperationResponse(OperationType.CLOSE_CONNECTION, OperationStatus.ERROR,
                        "Max number of connections reached");
                writer.write(eJson.toJson(responseObj));
                writer.flush();
            }
            writer.close();
        } catch (IOException e) {
            if (clientId != null) {
                clientTracker.removeById(clientId);
            }
            logger.error("General error in MessageProcessor", e);
        }
    }
}
