package dev.rassimok.api.klad;

import jakarta.inject.Singleton;

import java.util.concurrent.Semaphore;

/**
 * The sockets open on this instance. Each one is counted from the start of
 * its handshake to its close, and at least until its session lookup returns,
 * so handshakes sent at once cannot all find room while their lookups run,
 * and no more lookups run at once than there are slots.
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
