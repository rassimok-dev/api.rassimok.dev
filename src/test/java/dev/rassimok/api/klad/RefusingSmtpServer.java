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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A mail server that accepts any login, refuses every recipient and, like
 * many real ones, repeats the address in its reply. The init argument
 * {@link #KEY} sets the mail key the service logs in with; empty means none.
 */
public class RefusingSmtpServer implements QuarkusTestResourceLifecycleManager {

    static final String KEY = "key";
    static final int REFUSED_REPLY_CODE = 550;
    private static final String RECIPIENT_COMMAND = "RCPT TO:";

    private ServerSocket server;
    private String key = "stub-key";

    @Override
    public void init(Map<String, String> initArgs) {
        key = initArgs.getOrDefault(KEY, key);
    }

    @Override
    public Map<String, String> start() {
        try {
            server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Thread.ofVirtual().start(this::serve);
        Map<String, String> config = new HashMap<>(Map.of(
                "quarkus.mailer.mock", "false",
                "quarkus.mailer.host", server.getInetAddress().getHostAddress(),
                "quarkus.mailer.port", String.valueOf(server.getLocalPort()),
                "quarkus.mailer.tls", "false",
                "quarkus.mailer.start-tls", "DISABLED"));
        if (!key.isEmpty()) {
            config.put("quarkus.mailer.password", key);
        }
        return config;
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
                    if (command.startsWith("EHLO")) {
                        reply(output, "250-stub");
                        reply(output, "250 AUTH PLAIN");
                    } else if (command.startsWith("AUTH ")) {
                        reply(output, "235 2.7.0 Authentication successful");
                    } else if (command.startsWith(RECIPIENT_COMMAND)) {
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
