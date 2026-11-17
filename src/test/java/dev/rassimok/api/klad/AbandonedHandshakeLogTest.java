package dev.rassimok.api.klad;

import io.quarkus.test.common.DevServicesContext;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.startsWith;

// JVM only: a native test cannot see the binary's log from here. Without the
// refusal of a gone client, each abandoned handshake logged an error.
@QuarkusTest
class AbandonedHandshakeLogTest {

    private static final int ABANDONED_HANDSHAKES = 2 * Rules.SOCKETS_OPEN_MAX;
    private static final long PAUSE_MILLIS = 10;
    private static final long TIMEOUT_SECONDS = 10;
    private static final int RESET_ROUNDS = 3;
    // Per verticle instance: tasks that each spin for a moment and queue
    // themselves again, so every other task waits behind at least 100 of them.
    private static final int BUSY_TASKS_PER_INSTANCE = 100;
    private static final long BUSY_TASK_NANOS = 20_000;
    private static final String SWITCHING_PROTOCOLS = "HTTP/1.1 101 ";

    @TestHTTPResource("/")
    URI serverUri;

    DevServicesContext devServices;

    @Inject
    Vertx vertx;

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

    // Busy event loops let a reset land between toWebSocket() and the handshake,
    // which logged "Result is already complete"; opened, such a socket would
    // show its member connected with no close to come.
    @Test
    void shouldLogNoWarningAndLeaveNoMemberConnected_whenConnect_givenHandshakesResetAsLookupsReturnOnBusyServer()
            throws Exception {
        // given
        List<SocketRoute.Seat> seats = RaceRoute.SHARED.newSeats(Rules.SOCKETS_OPEN_MAX);
        DataSource dataSource = TestSql.devServicesDatabase(devServices);

        // when
        List<String> warnings = warningsWhile(() -> {
            for (int i = 0; i < RESET_ROUNDS; i++) {
                whileEventLoopsBusy(() -> SocketGuardsTest.resetAsLookupsReturn(serverUri, dataSource, seats));
            }
        });
        // The first and the last seat are in different rooms.
        List<Boolean> othersConnected = new ArrayList<>(othersConnected(seats.getFirst()));
        othersConnected.addAll(othersConnected(seats.getLast()));
        int openSockets = openSocketsUpToCap();

        // then
        assertThat(warnings, empty());
        assertThat(othersConnected, everyItem(equalTo(false)));
        assertThat(openSockets, equalTo(Rules.SOCKETS_OPEN_MAX));
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

    // Quarkus runs no more event loops than there are processors, and at
    // least 2. Each verticle instance gets the next loop, so twice as many
    // instances as processors reach every loop.
    private void whileEventLoopsBusy(Handshakes handshakes) throws Exception {
        AtomicBoolean isBusy = new AtomicBoolean(true);
        DeploymentOptions onEveryEventLoop = new DeploymentOptions()
                .setInstances(2 * Runtime.getRuntime().availableProcessors());
        String deployment = await(vertx.deployVerticle(() -> new BusyVerticle(isBusy), onEveryEventLoop));
        try {
            handshakes.send();
        } finally {
            isBusy.set(false);
            await(vertx.undeploy(deployment));
        }
    }

    // The first message of a race socket is the room's state.
    private List<Boolean> othersConnected(SocketRoute.Seat seat) throws IOException {
        List<Boolean> connected = new ArrayList<>();
        try (Socket socket = SocketGuardsTest.sendUpgradeRequest(serverUri, seat)) {
            assertThat(SocketGuardsTest.readHead(socket), startsWith(SWITCHING_PROTOCOLS));
            DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            input.readUnsignedByte();
            int length = input.readUnsignedByte();
            if (126 == length) {
                length = input.readUnsignedShort();
            }
            JsonObject state = new JsonObject(new String(input.readNBytes(length), StandardCharsets.UTF_8));
            state.getJsonArray("players").stream()
                    .map(JsonObject.class::cast)
                    .filter(player -> !player.getBoolean("you"))
                    .forEach(player -> connected.add(player.getBoolean("connected")));
        }
        return connected;
    }

    // Fresh seats, so no socket replaces another. A slot that never came
    // back shows as a refusal by the last handshake.
    private int openSocketsUpToCap() throws Exception {
        List<Socket> sockets = new ArrayList<>();
        try {
            RaceRoute.SHARED.forEachNewSeat(Rules.SOCKETS_OPEN_MAX, seat -> {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
                boolean isOpen = false;
                while (!isOpen && System.nanoTime() < deadline) {
                    Socket socket = SocketGuardsTest.sendUpgradeRequest(serverUri, seat);
                    isOpen = SocketGuardsTest.readHead(socket).startsWith(SWITCHING_PROTOCOLS);
                    if (isOpen) {
                        sockets.add(socket);
                    } else {
                        socket.close();
                        Thread.sleep(PAUSE_MILLIS);
                    }
                }
            });
        } finally {
            for (Socket socket : sockets) {
                socket.close();
            }
        }
        return sockets.size();
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    interface Handshakes {
        void send() throws Exception;
    }

    private static final class BusyVerticle extends AbstractVerticle {

        private final AtomicBoolean isBusy;

        BusyVerticle(AtomicBoolean isBusy) {
            this.isBusy = isBusy;
        }

        @Override
        public void start() {
            for (int i = 0; i < BUSY_TASKS_PER_INSTANCE; i++) {
                spin();
            }
        }

        private void spin() {
            context.runOnContext(ignored -> {
                long end = System.nanoTime() + BUSY_TASK_NANOS;
                while (System.nanoTime() < end) {
                    Thread.onSpinWait();
                }
                if (isBusy.get()) {
                    spin();
                }
            });
        }
    }
}
