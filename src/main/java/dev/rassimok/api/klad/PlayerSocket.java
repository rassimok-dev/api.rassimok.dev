package dev.rassimok.api.klad;

/**
 * One member's race socket as the room sees it. The room calls it under
 * RoomStore's lock, from any thread, so neither method may wait: each one
 * only queues its work on the socket's own event loop.
 */
interface PlayerSocket {

    void send(String message);

    // With close code 1000, so the client does not reconnect.
    void close(String reason);
}
