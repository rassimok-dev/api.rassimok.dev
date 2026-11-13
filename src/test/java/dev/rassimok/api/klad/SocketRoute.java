package dev.rassimok.api.klad;

import java.util.List;

/**
 * A socket path as the guard tests see it. Every route registered through
 * WebSocketHandshakeCheck gets one, so the guard tests run against it.
 */
interface SocketRoute {

    record Seat(String token, String path) {
    }

    record Refusal(String name, Seat seat, String statusLine) {
    }

    interface SeatUse {
        void use(Seat seat) throws Exception;
    }

    // A token and a path that this route upgrades now, fresh for each call.
    Seat newSeat();

    // Seats that can all hold a socket at once, all made before any is used.
    List<Seat> newSeats(int count);

    // The same seats, each handed over as soon as it is made, so that it is
    // used before any wait the route has for its seats can run out.
    void forEachNewSeat(int count, SeatUse use) throws Exception;

    // Handshakes that pass the shared checks and that the route refuses itself.
    List<Refusal> ownRefusals();

    // A message the route answers with reply().
    String request();

    String reply();

    // The answer to a text message the route cannot read.
    String badReply();

    // A message the route sends on its own, not as an answer.
    boolean isUnprompted(String message);
}
