package org.techhouse;

import java.io.IOException;
import org.techhouse.client.ClientMain;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws IOException {
        if (ClientMain.isClientInvocation(args)) {
            System.exit(ClientMain.run(args));
        } else {
            ServerMain.main(args);
        }
    }
}
