package dev.rassimok.api.klad;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

class OpenSocketsTest {

    private final OpenSockets openSockets = new OpenSockets();

    @Test
    void shouldOpen_whenTryOpen_givenOneSlotLeft() {
        // given
        openUpTo(Rules.SOCKETS_OPEN_MAX - 1);

        // when
        boolean isOpened = openSockets.tryOpen();

        // then
        assertThat(isOpened, equalTo(true));
    }

    @Test
    void shouldRefuse_whenTryOpen_givenCapReached() {
        // given
        openUpTo(Rules.SOCKETS_OPEN_MAX);

        // when
        boolean isOpened = openSockets.tryOpen();

        // then
        assertThat(isOpened, equalTo(false));
    }

    @Test
    void shouldOpen_whenTryOpen_givenCapReachedAndOneClosed() {
        // given
        openUpTo(Rules.SOCKETS_OPEN_MAX);
        openSockets.closed();

        // when
        boolean isOpened = openSockets.tryOpen();

        // then
        assertThat(isOpened, equalTo(true));
    }

    private void openUpTo(int sockets) {
        for (int i = 0; i < sockets; i++) {
            openSockets.tryOpen();
        }
    }
}
