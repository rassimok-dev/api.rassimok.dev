package dev.rassimok.api.klad;

import jakarta.inject.Singleton;

import java.util.concurrent.Semaphore;

/**
 * The sockets open on this instance. Each one is counted from the start of
 * its handshake to its close, so handshakes sent at once cannot all find
 * room while their session lookups run.
 */
@Singleton
public class OpenSockets {

    private final Semaphore slots = new Semaphore(Rules.SOCKETS_OPEN_MAX);

    boolean tryOpen() {
        return slots.tryAcquire();
    }

    void closed() {
        slots.release();
    }
}
