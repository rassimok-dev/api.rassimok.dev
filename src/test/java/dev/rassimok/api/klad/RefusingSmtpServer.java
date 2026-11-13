package dev.rassimok.api.klad;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * A mail server that refuses every recipient and, like many real ones,
 * repeats the address in its reply.
 */
public class RefusingSmtpServer implements QuarkusTestResourceLifecycleManager {

    static final int REFUSED_REPLY_CODE = 550;
    private static final String RECIPIENT_COMMAND = "RCPT TO:";

    private ServerSocket server;

    @Override
    public Map<String, String> start() {
        try {
            server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Thread.ofVirtual().start(this::serve);
        return Map.of(
                "quarkus.mailer.mock", "false",
                "quarkus.mailer.host", server.getInetAddress().getHostAddress(),
                "quarkus.mailer.port", String.valueOf(server.getLocalPort()),
                "quarkus.mailer.tls", "false",
                "quarkus.mailer.start-tls", "DISABLED");
    }

    @Override
    public void stop() {
        try {
            server.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void serve() {
        while (!server.isClosed()) {
            try (Socket client = server.accept();
                 BufferedReader input = new BufferedReader(
                         new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII))) {
                OutputStream output = client.getOutputStream();
                reply(output, "220 stub ESMTP");
                for (String line = input.readLine(); null != line; line = input.readLine()) {
                    String command = line.toUpperCase(Locale.ROOT);
                    if (command.startsWith(RECIPIENT_COMMAND)) {
                        String recipient = line.substring(RECIPIENT_COMMAND.length()).trim();
                        reply(output, REFUSED_REPLY_CODE + " 5.1.1 " + recipient + ": Recipient address rejected");
                    } else if (command.startsWith("QUIT")) {
                        reply(output, "221 bye");
                        break;
                    } else {
                        reply(output, "250 OK");
                    }
                }
            } catch (IOException e) {
                // The server socket was closed in stop(), or the client went away.
            }
        }
    }

    private static void reply(OutputStream output, String line) throws IOException {
        output.write((line + "\r\n").getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }
}
