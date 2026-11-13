package dev.rassimok.api.klad;

import io.vertx.ext.web.Router;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A socket route whose listener throws on every message. It lives in the
 * test sources, so only the JVM tests have it, never the native binary.
 */
@Singleton
public class ThrowingSocketRoute {

    static final String PATH = "/test/throwing-socket";

    private final AtomicInteger closes = new AtomicInteger();

    void register(@Observes Router router, WebSocketHandshakeCheck handshakeCheck) {
        router.get(PATH).handler(context -> handshakeCheck.upgrade(context, new ThrowingEndpoint()));
    }

    int closes() {
        return closes.get();
    }

    private final class ThrowingEndpoint implements SocketEndpoint {

        @Override
        public int refusalStatus(LobbyPlayer player) {
            return NO_REFUSAL;
        }

        @Override
        public SocketListener open(LobbyPlayer player, SocketSender sender) {
            return new SocketListener() {
                @Override
                public void onText(String message) {
                    throw new IllegalStateException("thrown by the test listener");
                }

                @Override
                public void onClose() {
                    closes.incrementAndGet();
                }
            };
        }
    }
}
