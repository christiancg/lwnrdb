package org.techhouse.conn;

import java.io.*;
import java.net.Socket;

public class MessageProcessor extends Thread {
    private final Socket socket;

    public MessageProcessor(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {
        BufferedReader reader = null;
        BufferedWriter writer = null;
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
                        System.out.println(message);
                        writer.write("ack");
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
