package org.techhouse.conn;

import com.google.gson.Gson;
import org.techhouse.ex.InvalidCommandException;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.req.RequestParser;

import java.io.*;
import java.net.Socket;

public class MessageProcessor extends Thread {
    private final Gson gson = new Gson();
    private final OperationProcessor operationProcessor = IocContainer.get(OperationProcessor.class);
    private final Socket socket;

    public MessageProcessor(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {
        BufferedReader reader;
        BufferedWriter writer;
        try {
            System.out.println("Opening connection");
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            var close = false;
            var message = "";
            while(!close) {
                message = reader.readLine();
                if (message != null && !message.isBlank()) {
                    if (message.equalsIgnoreCase("close")) {
                        System.out.println("Closing connection");
                        writer.write("Bye!");
                        writer.close();
                        close = true;
                    } else {
                        var response = "";
                        try {
                            final var parsedMessage = RequestParser.parseRequest(message);
                            final var responseObj = operationProcessor.processMessage(parsedMessage);
                            response = gson.toJson(responseObj);
                        } catch (InvalidCommandException exception) {
                            response = exception.getMessage();
                        }
                        writer.write(response);
                        writer.flush();
                    }
                } else {
                    System.out.println("Empty message");
                    writer.write("Empty message");
                    writer.flush();
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
