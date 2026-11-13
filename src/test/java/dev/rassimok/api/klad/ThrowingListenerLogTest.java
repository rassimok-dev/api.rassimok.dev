package dev.rassimok.api.klad;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;

// JVM only: the throwing route is a test bean, and a native test cannot see
// the binary's log from here. Vert.x logs an error for each message whose
// handler throws, unless the check drops the socket first.
@QuarkusTest
class ThrowingListenerLogTest {

    private static final long TIMEOUT_SECONDS = 10;
    private static final long PAUSE_MILLIS = 10;
    private static final String SWITCHING_PROTOCOLS = "HTTP/1.1 101 ";

    @TestHTTPResource("/")
    URI serverUri;

    @Inject
    ThrowingSocketRoute route;

    @Test
    void shouldDropSocketWithoutLogAndCloseOnce_whenSendText_givenListenerThrows() throws Exception {
        // given
        SocketRoute.Seat seat = new SocketRoute.Seat(TestPlayers.createGuest("Racer", Rules.KIND_HUMAN),
                ThrowingSocketRoute.PATH);
        int closesBefore = route.closes();
        AtomicInteger firstByte = new AtomicInteger();

        // when
        List<String> warnings = AbandonedHandshakeLogTest.warningsWhile(() -> {
            try (Socket socket = SocketGuardsTest.sendUpgradeRequest(serverUri, seat)) {
                SocketGuardsTest.readHead(socket);
                socket.getOutputStream().write(SocketGuardsTest.maskedTextFrame("hello"));
                socket.getOutputStream().write(SocketGuardsTest.maskedTextFrame("again"));
                firstByte.set(readAfterDrop(socket));
            }
        });
        int closesAfterDrop = route.closes();
        int openSockets = openSocketsUpToCap(seat);

        // then
        assertThat(firstByte.get(), equalTo(-1));
        assertThat(warnings, empty());
        assertThat(closesAfterDrop, equalTo(closesBefore + 1));
        assertThat(openSockets, equalTo(Rules.SOCKETS_OPEN_MAX));
    }

    // The test route has no room, so one player can hold every slot. A slot
    // the drop kept would show as a refusal by the last handshake.
    private int openSocketsUpToCap(SocketRoute.Seat seat) throws Exception {
        List<Socket> sockets = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        try {
            while (sockets.size() < Rules.SOCKETS_OPEN_MAX && System.nanoTime() < deadline) {
                Socket socket = SocketGuardsTest.sendUpgradeRequest(serverUri, seat);
                if (SocketGuardsTest.readHead(socket).startsWith(SWITCHING_PROTOCOLS)) {
                    sockets.add(socket);
                } else {
                    socket.close();
                    Thread.sleep(PAUSE_MILLIS);
                }
            }
        } finally {
            for (Socket socket : sockets) {
                socket.close();
            }
        }
        return sockets.size();
    }

    // A drop with the second frame still unread may reach the client as a
    // reset; either way no close frame came first.
    private static int readAfterDrop(Socket socket) throws IOException {
        int next;
        try {
            next = socket.getInputStream().read();
        } catch (SocketException e) {
            next = -1;
        }
        return next;
    }
}
