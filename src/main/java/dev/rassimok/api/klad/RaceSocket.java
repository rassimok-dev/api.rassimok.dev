package dev.rassimok.api.klad;

import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

import java.util.UUID;

/**
 * The race socket of a room, /race/<room> (contract section 8).
 * WebSocketHandshakeCheck guards the handshake and the reads; RoomStore
 * keeps the race.
 */
@Singleton
public class RaceSocket {

    private static final String PATH = "/race/:room";
    private static final String ROOM_PARAMETER = "room";

    void register(@Observes Router router, WebSocketHandshakeCheck handshakeCheck, RoomStore rooms) {
        router.get(PATH).handler(context ->
                handshakeCheck.upgrade(context, new RoomEndpoint(rooms, context.pathParam(ROOM_PARAMETER))));
    }

    // Moves the races on while no message comes in: the countdown, go, the
    // end of a race and the idle close. Each rule reads RoomStore's clock,
    // so a late or missed tick only delays a message.
    void startTicking(@Observes StartupEvent event, Vertx vertx, RoomStore rooms) {
        vertx.setPeriodic(Rules.RACE_TICK.toMillis(), ignored -> rooms.advance());
    }

    private record RoomEndpoint(RoomStore rooms, String roomId) implements SocketEndpoint {

        @Override
        public int refusalStatus(LobbyPlayer player) {
            return rooms.raceRefusal(roomId, player.id());
        }

        @Override
        public SocketListener open(LobbyPlayer player, SocketSender sender) {
            PlayerSocket socket = new QueuedSocket(Vertx.currentContext(), sender);
            rooms.connect(roomId, player.id(), socket);
            return new RaceListener(rooms, roomId, player.id(), socket);
        }
    }

    private record RaceListener(RoomStore rooms, String roomId, UUID playerId, PlayerSocket socket)
            implements SocketListener {

        @Override
        public void onText(String message) {
            rooms.receive(roomId, playerId, socket, RaceMessage.parse(message));
        }

        @Override
        public void onClose() {
            rooms.disconnect(roomId, playerId, socket);
        }
    }

    // A sender is used on its socket's event loop only, and a room calls this
    // from any thread under RoomStore's lock, so every call is queued there.
    // Queued even from that loop: a direct send can drop the socket, whose
    // close would then come back into the room in the middle of its work.
    private static final class QueuedSocket implements PlayerSocket {

        private final Context context;
        private final SocketSender sender;

        private QueuedSocket(Context context, SocketSender sender) {
            this.context = context;
            this.sender = sender;
        }

        @Override
        public void send(String message) {
            context.runOnContext(ignored -> sender.send(message));
        }

        @Override
        public void close(String reason) {
            context.runOnContext(ignored -> sender.close(SocketSender.NORMAL_CLOSURE, reason));
        }
    }
}
