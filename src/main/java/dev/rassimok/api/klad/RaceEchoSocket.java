package dev.rassimok.api.klad;

import io.vertx.ext.web.Router;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

import java.nio.charset.StandardCharsets;

/**
 * Answers every text message with the same text. A spike to prove the
 * race socket works in the native image; WebSocketHandshakeCheck guards the
 * handshake and the reads.
 */
@Singleton
public class RaceEchoSocket {

    private static final String PATH = "/race/echo";
    private static final String TOO_LONG = "message_too_long";

    void register(@Observes Router router, WebSocketHandshakeCheck handshakeCheck) {
        router.get(PATH).handler(context -> handshakeCheck.upgrade(context, RaceEchoSocket::echo));
    }

    // Checked here and not only by the frame size limit: over that limit the
    // connection is dropped without a close code.
    private static void echo(SocketSender sender, String message) {
        boolean isTooLong = Rules.SOCKET_MESSAGE_BYTES_MAX < message.getBytes(StandardCharsets.UTF_8).length;
        if (isTooLong) {
            sender.close(TOO_LONG);
        } else {
            sender.send(message);
        }
    }
}
