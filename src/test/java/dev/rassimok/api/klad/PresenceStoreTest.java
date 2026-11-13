package dev.rassimok.api.klad;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PresenceStoreTest {

    private static final Instant START = Instant.parse("2026-11-13T12:00:00Z");
    private static final LobbyPlayer ALICE = new LobbyPlayer(UUID.randomUUID(), "Alice", Rules.KIND_HUMAN, true);
    private static final LobbyPlayer BOB = new LobbyPlayer(UUID.randomUUID(), "Bob", Rules.KIND_AGENT, false);

    private final FakeClock clock = new FakeClock(START);
    private final PresenceStore presence = new PresenceStore(clock);

    @Test
    void shouldListPlayer_whenOnline_givenHeartbeat() {
        // given
        presence.heartbeat(ALICE);

        // when
        PresenceStore.PresenceView online = presence.online();

        // then
        assertThat(online, equalTo(view(new PresenceStore.OnlinePlayer("Alice", true, Rules.KIND_HUMAN))));
    }

    @Test
    void shouldListNobody_whenOnline_givenNoHeartbeat() {
        // when
        PresenceStore.PresenceView online = presence.online();

        // then
        assertThat(online, equalTo(view()));
    }

    @Test
    void shouldKeepPlayer_whenOnline_givenHeartbeatExactly60SecondsAgo() {
        // given
        presence.heartbeat(ALICE);
        clock.advance(Rules.PRESENCE_WINDOW);

        // when
        PresenceStore.PresenceView online = presence.online();

        // then
        assertThat(online, equalTo(view(online(ALICE))));
    }

    @Test
    void shouldDropPlayer_whenOnline_givenHeartbeatOlderThan60Seconds() {
        // given
        presence.heartbeat(ALICE);
        clock.advance(Rules.PRESENCE_WINDOW.plusMillis(1));

        // when
        PresenceStore.PresenceView online = presence.online();

        // then
        assertThat(online, equalTo(view()));
    }

    @Test
    void shouldDropOnlyThatPlayer_whenForget_givenTwoPlayersOnline() {
        // given
        presence.heartbeat(ALICE);
        presence.heartbeat(BOB);

        // when
        presence.forget(ALICE.id());

        // then
        assertThat(presence.online(), equalTo(view(online(BOB))));
    }

    @Test
    void shouldCountFromLastHeartbeat_whenOnline_givenSecondHeartbeat() {
        // given
        presence.heartbeat(ALICE);
        clock.advance(Duration.ofSeconds(50));
        presence.heartbeat(ALICE);
        clock.advance(Duration.ofSeconds(50));

        // when
        PresenceStore.PresenceView online = presence.online();

        // then
        assertThat(online, equalTo(view(online(ALICE))));
    }

    @Test
    void shouldListInOrderOfComingOnline_whenOnline_givenEarlierPlayerBeatAgain() {
        // given
        presence.heartbeat(ALICE);
        presence.heartbeat(BOB);
        presence.heartbeat(ALICE);

        // when
        PresenceStore.PresenceView online = presence.online();

        // then
        assertThat(online,
                equalTo(view(online(ALICE), new PresenceStore.OnlinePlayer("Bob", false, Rules.KIND_AGENT))));
    }

    @Test
    void shouldShowNewNick_whenOnline_givenHeartbeatAfterRename() {
        // given
        presence.heartbeat(ALICE);
        presence.heartbeat(new LobbyPlayer(ALICE.id(), "Alicia", ALICE.kind(), ALICE.isGuest()));

        // when
        PresenceStore.PresenceView online = presence.online();

        // then
        assertThat(online, equalTo(view(new PresenceStore.OnlinePlayer("Alicia", true, Rules.KIND_HUMAN))));
    }

    @Test
    void shouldListBoth_whenOnline_givenTwoGuestsWithSameNick() {
        // given
        LobbyPlayer otherAlice = new LobbyPlayer(UUID.randomUUID(), "Alice", Rules.KIND_HUMAN, true);
        presence.heartbeat(ALICE);
        presence.heartbeat(otherAlice);

        // when
        PresenceStore.PresenceView online = presence.online();

        // then
        assertThat(online, equalTo(view(online(ALICE), online(otherAlice))));
    }

    @Test
    void shouldRefuseNewPlayer_whenHeartbeat_givenCapReached() {
        // given
        fillUpTo(Rules.PRESENCE_MAX);

        // when
        ApiException exception = assertThrows(ApiException.class, () -> presence.heartbeat(ALICE));

        // then
        assertThat(exception.status(), equalTo(Response.Status.TOO_MANY_REQUESTS));
        assertThat(exception.code(), equalTo(ApiException.RATE_LIMITED));
        assertThat(presence.online().players().size(), equalTo(Rules.PRESENCE_MAX));
    }

    @Test
    void shouldAcceptKnownPlayer_whenHeartbeat_givenCapReached() {
        // given
        presence.heartbeat(ALICE);
        fillUpTo(Rules.PRESENCE_MAX - 1);

        // when
        presence.heartbeat(ALICE);

        // then
        assertThat(presence.online().players().size(), equalTo(Rules.PRESENCE_MAX));
    }

    @Test
    void shouldAcceptNewPlayer_whenHeartbeat_givenCapReachedByExpiredEntries() {
        // given
        fillUpTo(Rules.PRESENCE_MAX);
        clock.advance(Rules.PRESENCE_WINDOW.plusMillis(1));

        // when
        presence.heartbeat(ALICE);

        // then
        assertThat(presence.online(), equalTo(view(online(ALICE))));
    }

    @Test
    void shouldAcceptExactlyTheCap_whenHeartbeat_givenManyNewPlayersAtOnce() throws Exception {
        // given
        int players = Rules.PRESENCE_MAX + 200;
        List<Callable<Boolean>> heartbeats = IntStream.range(0, players)
                .<Callable<Boolean>>mapToObj(i -> () -> isAccepted(player("Player" + i)))
                .toList();

        // when
        List<Future<Boolean>> results;
        try (ExecutorService executor = Executors.newFixedThreadPool(16)) {
            results = executor.invokeAll(heartbeats);
        }

        // then
        long accepted = 0;
        for (Future<Boolean> result : results) {
            accepted += result.get() ? 1 : 0;
        }
        assertThat(accepted, equalTo((long) Rules.PRESENCE_MAX));
        assertThat(presence.online().players().size(), equalTo(Rules.PRESENCE_MAX));
    }

    private boolean isAccepted(LobbyPlayer player) {
        boolean isAccepted = true;
        try {
            presence.heartbeat(player);
        } catch (ApiException e) {
            isAccepted = false;
        }
        return isAccepted;
    }

    private void fillUpTo(int entries) {
        for (int i = 0; i < entries; i++) {
            presence.heartbeat(player("Filler" + i));
        }
    }

    private static LobbyPlayer player(String nick) {
        return new LobbyPlayer(UUID.randomUUID(), nick, Rules.KIND_HUMAN, true);
    }

    private static PresenceStore.OnlinePlayer online(LobbyPlayer player) {
        return new PresenceStore.OnlinePlayer(player.nick(), player.isGuest(), player.kind());
    }

    private static PresenceStore.PresenceView view(PresenceStore.OnlinePlayer... players) {
        return new PresenceStore.PresenceView(List.of(players));
    }
}
