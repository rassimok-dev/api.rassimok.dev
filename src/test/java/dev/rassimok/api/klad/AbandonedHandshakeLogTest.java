package dev.rassimok.api.klad;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.Socket;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;

// JVM only: a native test cannot see the binary's log from here. Without the
// refusal of a gone client, each abandoned handshake logged an error.
@QuarkusTest
class AbandonedHandshakeLogTest {

    private static final int ABANDONED_HANDSHAKES = 2 * Rules.SOCKETS_OPEN_MAX;
    private static final long PAUSE_MILLIS = 10;

    @TestHTTPResource("/")
    URI serverUri;

    @Test
    void shouldLogNoWarning_whenConnect_givenHandshakesAbandonedDuringLookup() throws Exception {
        // given
        SocketRoute.Seat seat = RaceRoute.SHARED.newSeat();

        // when
        List<String> warnings = warningsWhile(() -> {
            for (int i = 0; i < ABANDONED_HANDSHAKES; i++) {
                SocketGuardsTest.sendUpgradeRequest(serverUri, seat).close();
                Thread.sleep(PAUSE_MILLIS);
            }
        });

        // then
        assertThat(warnings, empty());
    }

    // Closed as soon as the answer is read, so the close can land while the
    // second handshake of the pair is upgraded, which logged an error.
    @Test
    void shouldLogNoWarning_whenConnect_givenTwoHandshakesInOneWrite() throws Exception {
        // given
        RaceRoute route = RaceRoute.SHARED;

        // when
        List<String> warnings = warningsWhile(() -> {
            for (int i = 0; i < ABANDONED_HANDSHAKES; i++) {
                try (Socket socket = SocketGuardsTest.sendTwoUpgradeRequests(serverUri, route.newSeat())) {
                    SocketGuardsTest.readHead(socket);
                }
            }
        });

        // then
        assertThat(warnings, empty());
    }

    static List<String> warningsWhile(Handshakes handshakes) throws Exception {
        List<String> warnings = new CopyOnWriteArrayList<>();
        Handler collector = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (Level.WARNING.intValue() <= record.getLevel().intValue()) {
                    warnings.add(record.getLoggerName() + ": " + record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger root = Logger.getLogger("");
        root.addHandler(collector);
        try {
            handshakes.send();
            // The lookups end after their clients have gone, so give them a moment.
            Thread.sleep(1000);
        } finally {
            root.removeHandler(collector);
        }
        return warnings;
    }

    interface Handshakes {
        void send() throws Exception;
    }
}
