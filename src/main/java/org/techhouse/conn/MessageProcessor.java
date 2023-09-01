package org.techhouse.conn;

import com.google.gson.Gson;
import org.techhouse.ex.InvalidCommandException;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.resp.OperationResponse;

import java.io.*;
import java.net.Socket;
import java.util.UUID;

public class MessageProcessor implements Runnable {
    private final Gson gson = IocContainer.get(Gson.class);
    private final OperationProcessor operationProcessor = IocContainer.get(OperationProcessor.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
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
            System.out.println("Opening connection");
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
                            response = gson.toJson(responseObj);
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
                writer.write(gson.toJson(responseObj));
                writer.flush();
            }
            writer.close();
        } catch (IOException e) {
            if (clientId != null) {
                clientTracker.removeById(clientId);
            }
            System.out.println("General error in MessageProcessor: " + e.getMessage());
        }
    }
}
