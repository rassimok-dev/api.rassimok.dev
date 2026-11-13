package dev.rassimok.api.klad;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RoomStoreTest {

    private static final Instant START = Instant.parse("2026-11-13T12:00:00Z");
    private static final String UNKNOWN_ID = "AAAAAAAAAA";
    private static final String LOBBY = "lobby";
    private static final String STARTING = "starting";
    private static final Duration JUST_OVER_SILENCE = Rules.LOBBY_MEMBER_SILENCE.plusMillis(1);

    private final LobbyPlayer alice = player("Alice");
    private final LobbyPlayer bob = player("Bob");
    private final LobbyPlayer carol = player("Carol");
    private final LobbyPlayer dave = player("Dave");
    private final LobbyPlayer eve = player("Eve");
    private final FakeClock clock = new FakeClock(START);
    private final RoomStore rooms = new RoomStore(clock);

    @Test
    void shouldMakeCallerHostAndOnlyPlayer_whenCreate() {
        // when
        String id = rooms.create(alice);

        // then
        assertThat(rooms.find(id, alice), equalTo(room(id, LOBBY, alice, member(alice, false, true))));
    }

    @Test
    void shouldGiveDistinctIdsOfTenUrlSafeCharacters_whenCreate() {
        // when
        List<String> ids = IntStream.range(0, Rules.ROOMS_MAX).mapToObj(i -> rooms.create(player("Host" + i))).toList();

        // then
        assertThat(ids, everyItem(matchesPattern("[A-Za-z0-9_-]{10}")));
        assertThat(new HashSet<>(ids).size(), equalTo(Rules.ROOMS_MAX));
    }

    @Test
    void shouldCloseOldRoom_whenCreate_givenCallerHostsRoomWithOthers() {
        // given
        String oldId = rooms.create(alice);
        rooms.join(oldId, bob);

        // when
        String newId = rooms.create(alice);

        // then
        assertNotFound(assertThrows(ApiException.class, () -> rooms.find(oldId, bob)));
        assertThat(rooms.find(newId, alice), equalTo(room(newId, LOBBY, alice, member(alice, false, true))));
    }

    @Test
    void shouldCloseOldRoom_whenCreate_givenCallerHostsStartingRoom() {
        // given
        String oldId = startedRoom();

        // when
        rooms.create(alice);

        // then
        assertNotFound(assertThrows(ApiException.class, () -> rooms.find(oldId, bob)));
    }

    @Test
    void shouldCloseOldRoom_whenCreate_givenCallerLastHostOfEmptyRoom() {
        // given
        String oldId = rooms.create(alice);
        rooms.leave(oldId, alice);

        // when
        rooms.create(alice);

        // then
        assertNotFound(assertThrows(ApiException.class, () -> rooms.find(oldId, bob)));
    }

    @Test
    void shouldLeaveOtherRoom_whenCreate_givenCallerGuestInOtherRoom() {
        // given
        String otherId = rooms.create(alice);
        rooms.join(otherId, bob);

        // when
        String newId = rooms.create(bob);

        // then
        assertThat(rooms.find(otherId, alice), equalTo(room(otherId, LOBBY, alice, member(alice, false, true))));
        assertThat(rooms.find(newId, bob), equalTo(room(newId, LOBBY, bob, member(bob, false, true))));
    }

    @Test
    void shouldRefuse_whenCreate_givenRoomCapReached() {
        // given
        fillRooms(Rules.ROOMS_MAX);

        // when
        ApiException exception = assertThrows(ApiException.class, () -> rooms.create(alice));

        // then
        assertError(exception, Response.Status.TOO_MANY_REQUESTS, ApiException.RATE_LIMITED);
    }

    @Test
    void shouldReplaceRoom_whenCreate_givenRoomCapReachedAndCallerHostsRoom() {
        // given
        String oldId = rooms.create(alice);
        fillRooms(Rules.ROOMS_MAX - 1);

        // when
        String newId = rooms.create(alice);

        // then
        assertThat(rooms.find(newId, alice), equalTo(room(newId, LOBBY, alice, member(alice, false, true))));
        assertNotFound(assertThrows(ApiException.class, () -> rooms.find(oldId, alice)));
        assertError(assertThrows(ApiException.class, () -> rooms.create(bob)),
                Response.Status.TOO_MANY_REQUESTS, ApiException.RATE_LIMITED);
    }

    @Test
    void shouldMarkOnlyOwnEntryAsYou_whenFind_givenTwoPlayersWithSameNickAndKind() {
        // given
        LobbyPlayer firstTwin = player("Twin");
        LobbyPlayer secondTwin = player("Twin");
        String id = rooms.create(firstTwin);
        rooms.join(id, secondTwin);
        RoomView.RoomPlayer host = new RoomView.RoomPlayer("Twin", Rules.KIND_HUMAN, false, true, false);
        RoomView.RoomPlayer guest = new RoomView.RoomPlayer("Twin", Rules.KIND_HUMAN, false, false, false);

        // when
        RoomView seenByFirst = rooms.find(id, firstTwin);
        RoomView seenBySecond = rooms.find(id, secondTwin);

        // then
        assertThat(seenByFirst.players(), equalTo(List.of(
                new RoomView.RoomPlayer("Twin", Rules.KIND_HUMAN, false, true, true), guest)));
        assertThat(seenBySecond.players(), equalTo(List.of(
                host, new RoomView.RoomPlayer("Twin", Rules.KIND_HUMAN, false, false, true))));
    }

    @Test
    void shouldStayInRoom_whenCreate_givenRoomCapReachedAndCallerGuestInRoom() {
        // given
        String id = rooms.create(alice);
        rooms.join(id, bob);
        fillRooms(Rules.ROOMS_MAX - 1);

        // when
        ApiException exception = assertThrows(ApiException.class, () -> rooms.create(bob));

        // then
        assertError(exception, Response.Status.TOO_MANY_REQUESTS, ApiException.RATE_LIMITED);
        assertThat(rooms.find(id, bob),
                equalTo(roomSeenBy(bob, id, LOBBY, alice, member(alice, false, true), member(bob, false, false))));
    }

    @Test
    void shouldCreateRoom_whenCreate_givenCapReachedByRoomsAbandonedJustOverElevenMinutesAgo() {
        // given
        fillRooms(Rules.ROOMS_MAX);
        clock.advance(Rules.LOBBY_MEMBER_SILENCE.plus(Rules.EMPTY_ROOM_LIFETIME).plusMillis(1));

        // when
        String id = rooms.create(alice);

        // then
        assertThat(rooms.find(id, alice), equalTo(room(id, LOBBY, alice, member(alice, false, true))));
    }

    @Test
    void shouldRefuse_whenCreate_givenCapReachedByRoomsAbandonedElevenMinutesAgo() {
        // given
        fillRooms(Rules.ROOMS_MAX);
        clock.advance(Rules.LOBBY_MEMBER_SILENCE.plus(Rules.EMPTY_ROOM_LIFETIME));

        // when
        ApiException exception = assertThrows(ApiException.class, () -> rooms.create(alice));

        // then
        assertError(exception, Response.Status.TOO_MANY_REQUESTS, ApiException.RATE_LIMITED);
    }

    @Test
    void shouldCreateExactlyTheCap_whenCreate_givenManyPlayersAtOnce() throws Exception {
        // given
        List<Callable<Boolean>> creates = IntStream.range(0, Rules.ROOMS_MAX + 50)
                .<Callable<Boolean>>mapToObj(i -> () -> isAccepted(() -> rooms.create(player("Host" + i))))
                .toList();

        // when
        long accepted = countAccepted(creates);

        // then
        assertThat(accepted, equalTo((long) Rules.ROOMS_MAX));
        assertError(assertThrows(ApiException.class, () -> rooms.create(alice)),
                Response.Status.TOO_MANY_REQUESTS, ApiException.RATE_LIMITED);
    }

    @Test
    void shouldAnswerNotFound_whenFind_givenUnknownId() {
        // when
        ApiException exception = assertThrows(ApiException.class, () -> rooms.find(UNKNOWN_ID, alice));

        // then
        assertNotFound(exception);
    }

    @Test
    void shouldShowNewNick_whenFind_givenMemberRenamed() {
        // given
        String id = rooms.create(alice);
        LobbyPlayer renamed = new LobbyPlayer(alice.id(), "Alicia", alice.kind(), alice.isGuest());

        // when
        RoomView room = rooms.find(id, renamed);

        // then
        assertThat(room, equalTo(room(id, LOBBY, renamed, member(renamed, false, true))));
    }

    @Test
    void shouldAddPlayersInJoinOrder_whenJoin() {
        // given
        String id = rooms.create(alice);
        rooms.join(id, bob);

        // when
        RoomView room = rooms.join(id, carol);

        // then
        assertThat(room, equalTo(roomSeenBy(carol, id, LOBBY, alice,
                member(alice, false, true), member(bob, false, false), member(carol, false, false))));
    }

    @Test
    void shouldRefuse_whenJoin_givenFullRoom() {
        // given
        String id = fullRoom();

        // when
        ApiException exception = assertThrows(ApiException.class, () -> rooms.join(id, eve));

        // then
        assertError(exception, Response.Status.CONFLICT, ApiException.ROOM_FULL);
    }

    @Test
    void shouldReturnRoom_whenJoin_givenPlayerAlreadyInFullRoom() {
        // given
        String id = fullRoom();

        // when
        RoomView room = rooms.join(id, dave);

        // then
        assertThat(room, equalTo(roomSeenBy(dave, id, LOBBY, alice, member(alice, false, true), member(bob, false, false),
                member(carol, false, false), member(dave, false, false))));
    }

    @Test
    void shouldReturnRoom_whenJoin_givenMemberOfStartingRoom() {
        // given
        String id = startedRoom();

        // when
        RoomView room = rooms.join(id, bob);

        // then
        assertThat(room, equalTo(roomSeenBy(bob, id, STARTING, alice, member(alice, true, true), member(bob, true, false))));
    }

    @Test
    void shouldRefuse_whenJoin_givenRoomStarting() {
        // given
        String id = startedRoom();

        // when
        ApiException exception = assertThrows(ApiException.class, () -> rooms.join(id, carol));

        // then
        assertError(exception, Response.Status.CONFLICT, ApiException.NOT_IN_LOBBY);
    }

    @Test
    void shouldLeaveFirstRoom_whenJoin_givenPlayerInOtherRoom() {
        // given
        String firstId = rooms.create(alice);
        rooms.join(firstId, carol);
        String secondId = rooms.create(bob);

        // when
        RoomView second = rooms.join(secondId, carol);

        // then
        assertThat(second,
                equalTo(roomSeenBy(carol, secondId, LOBBY, bob, member(bob, false, true), member(carol, false, false))));
        assertThat(rooms.find(firstId, alice), equalTo(room(firstId, LOBBY, alice, member(alice, false, true))));
    }

    @Test
    void shouldStayInFirstRoom_whenJoin_givenSecondRoomFull() {
        // given
        String fullId = fullRoom();
        String firstId = rooms.create(eve);
        LobbyPlayer frank = player("Frank");
        rooms.join(firstId, frank);

        // when
        ApiException exception = assertThrows(ApiException.class, () -> rooms.join(fullId, frank));

        // then
        assertError(exception, Response.Status.CONFLICT, ApiException.ROOM_FULL);
        assertThat(rooms.find(firstId, eve), equalTo(room(firstId, LOBBY, eve,
                member(eve, false, true), member(frank, false, false))));
    }

    @Test
    void shouldMakeJoinerHost_whenJoin_givenEmptyRoom() {
        // given
        String id = rooms.create(alice);
        rooms.leave(id, alice);

        // when
        RoomView room = rooms.join(id, bob);

        // then
        assertThat(room, equalTo(room(id, LOBBY, bob, member(bob, false, true))));
    }

    @Test
    void shouldAnswerNotFound_whenJoin_givenUnknownId() {
        // when
        ApiException exception = assertThrows(ApiException.class, () -> rooms.join(UNKNOWN_ID, alice));

        // then
        assertNotFound(exception);
    }

    @Test
    void shouldTakeExactlyThreeMore_whenJoin_givenEightPlayersAtOnce() throws Exception {
        // given
        String id = rooms.create(alice);
        List<Callable<Boolean>> joins = IntStream.range(0, 8)
                .<Callable<Boolean>>mapToObj(i -> () -> isAccepted(() -> rooms.join(id, player("Joiner" + i))))
                .toList();

        // when
        long accepted = countAccepted(joins);

        // then
        assertThat(accepted, equalTo((long) Rules.ROOM_SIZE - 1));
        assertThat(rooms.find(id, alice).players().size(), equalTo(Rules.ROOM_SIZE));
    }

    @Test
    void shouldRemovePlayer_whenLeave() {
        // given
        String id = rooms.create(alice);
        rooms.join(id, bob);

        // when
        rooms.leave(id, bob);

        // then
        assertThat(rooms.find(id, alice), equalTo(room(id, LOBBY, alice, member(alice, false, true))));
    }

    @Test
    void shouldHandHostToNextInJoinOrder_whenLeave_givenHostLeaves() {
        // given
        String id = rooms.create(alice);
        rooms.join(id, bob);
        rooms.join(id, carol);

        // when
        rooms.leave(id, alice);

        // then
        assertThat(rooms.find(id, bob),
                equalTo(room(id, LOBBY, bob, member(bob, false, true), member(carol, false, false))));
    }

    @Test
    void shouldChangeNothing_whenLeave_givenPlayerNotInRoom() {
        // given
        String id = rooms.create(alice);

        // when
        rooms.leave(id, bob);

        // then
        assertThat(rooms.find(id, alice), equalTo(room(id, LOBBY, alice, member(alice, false, true))));
    }

    @Test
    void shouldShowNoHostAndNoPlayers_whenFind_givenEveryoneLeft() {
        // given
        String id = rooms.create(alice);
        rooms.leave(id, alice);

        // when
        RoomView room = rooms.find(id, bob);

        // then
        assertThat(room, equalTo(new RoomView(id, LOBBY, null, List.of(), Rules.LEVEL_MIN, Rules.SHAPE_LANDSCAPE,
                Rules.ROOM_SIZE)));
    }

    @Test
    void shouldRemovePlayer_whenLeave_givenRoomStarting() {
        // given
        String id = startedRoom();

        // when
        rooms.leave(id, alice);

        // then
        assertThat(rooms.find(id, bob), equalTo(room(id, STARTING, bob, member(bob, true, true))));
    }

    @Test
    void shouldRemovePlayerAndHandOverHost_whenForget_givenHostInLobby() {
        // given
        String id = rooms.create(alice);
        rooms.join(id, bob);
        rooms.join(id, carol);

        // when
        rooms.forget(alice.id());

        // then
        assertThat(rooms.find(id, bob),
                equalTo(roomSeenBy(bob, id, LOBBY, bob, member(bob, false, true), member(carol, false, false))));
    }

    @Test
    void shouldRemovePlayerAndHandOverHost_whenForget_givenRoomStarting() {
        // given
        String id = startedRoom();

        // when
        rooms.forget(alice.id());

        // then
        assertThat(rooms.find(id, bob), equalTo(room(id, STARTING, bob, member(bob, true, true))));
    }

    @Test
    void shouldChangeNothing_whenForget_givenPlayerInNoRoom() {
        // given
        String id = rooms.create(alice);

        // when
        rooms.forget(bob.id());

        // then
        assertThat(rooms.find(id, alice), equalTo(room(id, LOBBY, alice, member(alice, false, true))));
    }

    @Test
    void shouldAnswerNotFound_whenLeave_givenUnknownId() {
        // when
        ApiException exception = assertThrows(ApiException.class, () -> rooms.leave(UNKNOWN_ID, alice));

        // then
        assertNotFound(exception);
    }

    @Test
    void shouldShowReadyMark_whenSetReady() {
        // given
        String id = rooms.create(alice);
        rooms.join(id, bob);

        // when
        RoomView room = rooms.setReady(id, bob, true);

        // then
        assertThat(room, equalTo(roomSeenBy(bob, id, LOBBY, alice, member(alice, false, true), member(bob, true, false))));
    }

    @Test
    void shouldClearReadyMark_whenSetReady_givenReadyPlayer() {
        // given
        String id = rooms.create(alice);
        rooms.setReady(id, alice, true);

        // when
        RoomView room = rooms.setReady(id, alice, false);

        // then
        assertThat(room, equalTo(room(id, LOBBY, alice, member(alice, false, true))));
    }

    @Test
    void shouldAnswerNotFound_whenSetReady_givenPlayerNotInRoom() {
        // given
        String id = rooms.create(alice);

        // when
        ApiException exception = assertThrows(ApiException.class, () -> rooms.setReady(id, bob, true));

        // then
        assertNotFound(exception);
    }

    @Test
    void shouldRefuse_whenSetReady_givenRoomStarting() {
        // given
        String id = startedRoom();

        // when
        ApiException exception = assertThrows(ApiException.class, () -> rooms.setReady(id, bob, false));

        // then
        assertError(exception, Response.Status.CONFLICT, ApiException.NOT_IN_LOBBY);
    }

    @Test
    void shouldSetLevelAndShape_whenChooseLevel_givenHost() {
        // given
        String id = rooms.create(alice);

        // when
        RoomView room = rooms.chooseLevel(id, alice, Rules.LEVEL_MAX, Rules.SHAPE_PORTRAIT);

        // then
        assertThat(room, equalTo(new RoomView(id, LOBBY, new RoomView.Host("Alice"),
                List.of(new RoomView.RoomPlayer("Alice", Rules.KIND_HUMAN, false, true, true)), Rules.LEVEL_MAX, Rules.SHAPE_PORTRAIT, Rules.ROOM_SIZE)));
    }

    @Test
    void shouldRefuse_whenChooseLevel_givenMemberWhoIsNotHost() {
        // given
        String id = rooms.create(alice);
        rooms.join(id, bob);

        // when
        ApiException exception = assertThrows(ApiException.class,
                () -> rooms.chooseLevel(id, bob, Rules.LEVEL_MAX, Rules.SHAPE_PORTRAIT));

        // then
        assertError(exception, Response.Status.FORBIDDEN, ApiException.NOT_HOST);
    }

    @Test
    void shouldRefuse_whenChooseLevel_givenPlayerNotInRoom() {
        // given
        String id = rooms.create(alice);

        // when
        ApiException exception = assertThrows(ApiException.class,
                () -> rooms.chooseLevel(id, bob, Rules.LEVEL_MAX, Rules.SHAPE_PORTRAIT));

        // then
        assertError(exception, Response.Status.FORBIDDEN, ApiException.NOT_HOST);
    }

    @Test
    void shouldRefuse_whenChooseLevel_givenLastHostOfEmptyRoom() {
        // given
        String id = rooms.create(alice);
        rooms.leave(id, alice);

        // when
        ApiException exception = assertThrows(ApiException.class,
                () -> rooms.chooseLevel(id, alice, Rules.LEVEL_MAX, Rules.SHAPE_PORTRAIT));

        // then
        assertError(exception, Response.Status.FORBIDDEN, ApiException.NOT_HOST);
    }

    @Test
    void shouldRefuse_whenChooseLevel_givenRoomStarting() {
        // given
        String id = startedRoom();

        // when
        ApiException exception = assertThrows(ApiException.class,
                () -> rooms.chooseLevel(id, alice, Rules.LEVEL_MAX, Rules.SHAPE_PORTRAIT));

        // then
        assertError(exception, Response.Status.CONFLICT, ApiException.NOT_IN_LOBBY);
    }

    @Test
    void shouldMoveToStarting_whenStart_givenTwoReadyPlayers() {
        // given
        String id = rooms.create(alice);
        rooms.join(id, bob);
        rooms.setReady(id, alice, true);
        rooms.setReady(id, bob, true);

        // when
        RoomView room = rooms.start(id, alice);

        // then
        assertThat(room, equalTo(room(id, STARTING, alice, member(alice, true, true), member(bob, true, false))));
    }

    @Test
    void shouldRefuse_whenStart_givenOnlyReadyHost() {
        // given
        String id = rooms.create(alice);
        rooms.setReady(id, alice, true);

        // when
        ApiException exception = assertThrows(ApiException.class, () -> rooms.start(id, alice));

        // then
        assertError(exception, Response.Status.CONFLICT, ApiException.NOT_READY);
    }

    @Test
    void shouldRefuse_whenStart_givenPlayerNotReady() {
        // given
        String id = rooms.create(alice);
        rooms.join(id, bob);
        rooms.join(id, carol);
        rooms.setReady(id, alice, true);
        rooms.setReady(id, bob, true);

        // when
        ApiException exception = assertThrows(ApiException.class, () -> rooms.start(id, alice));

        // then
        assertError(exception, Response.Status.CONFLICT, ApiException.NOT_READY);
        assertThat(rooms.find(id, alice).state(), equalTo(LOBBY));
    }

    @Test
    void shouldRefuse_whenStart_givenMemberWhoIsNotHost() {
        // given
        String id = rooms.create(alice);
        rooms.join(id, bob);
        rooms.setReady(id, alice, true);
        rooms.setReady(id, bob, true);

        // when
        ApiException exception = assertThrows(ApiException.class, () -> rooms.start(id, bob));

        // then
        assertError(exception, Response.Status.FORBIDDEN, ApiException.NOT_HOST);
    }

    @Test
    void shouldRefuse_whenStart_givenPlayerNotInRoom() {
        // given
        String id = rooms.create(alice);
        rooms.join(id, bob);
        rooms.setReady(id, alice, true);
        rooms.setReady(id, bob, true);

        // when
        ApiException exception = assertThrows(ApiException.class, () -> rooms.start(id, carol));

        // then
        assertError(exception, Response.Status.FORBIDDEN, ApiException.NOT_HOST);
    }

    @Test
    void shouldAnswerNotFound_whenSetReadyChooseLevelOrStart_givenUnknownId() {
        // when
        ApiException ready = assertThrows(ApiException.class, () -> rooms.setReady(UNKNOWN_ID, alice, true));
        ApiException level = assertThrows(ApiException.class,
                () -> rooms.chooseLevel(UNKNOWN_ID, alice, Rules.LEVEL_MAX, Rules.SHAPE_PORTRAIT));
        ApiException start = assertThrows(ApiException.class, () -> rooms.start(UNKNOWN_ID, alice));

        // then
        assertNotFound(ready);
        assertNotFound(level);
        assertNotFound(start);
    }

    @Test
    void shouldRefuse_whenStart_givenRoomStarting() {
        // given
        String id = startedRoom();

        // when
        ApiException exception = assertThrows(ApiException.class, () -> rooms.start(id, alice));

        // then
        assertError(exception, Response.Status.CONFLICT, ApiException.NOT_IN_LOBBY);
    }

    @Test
    void shouldKeepMember_whenFind_givenMemberSeenExactly60SecondsAgo() {
        // given
        String id = rooms.create(alice);
        rooms.join(id, bob);
        clock.advance(Rules.LOBBY_MEMBER_SILENCE);

        // when
        RoomView room = rooms.find(id, alice);

        // then
        assertThat(room, equalTo(room(id, LOBBY, alice, member(alice, false, true), member(bob, false, false))));
    }

    @Test
    void shouldRemoveMember_whenFind_givenMemberSilentForMoreThan60Seconds() {
        // given
        String id = rooms.create(alice);
        rooms.join(id, bob);
        clock.advance(Duration.ofSeconds(30));
        rooms.find(id, alice);
        clock.advance(Duration.ofSeconds(30).plusMillis(1));

        // when
        RoomView room = rooms.find(id, alice);

        // then
        assertThat(room, equalTo(room(id, LOBBY, alice, member(alice, false, true))));
    }

    @Test
    void shouldRemoveMember_whenFind_givenSilentMemberPollsAgain() {
        // given
        String id = rooms.create(alice);
        rooms.join(id, bob);
        clock.advance(Duration.ofSeconds(30));
        rooms.find(id, alice);
        clock.advance(Duration.ofSeconds(30).plusMillis(1));

        // when
        RoomView room = rooms.find(id, bob);

        // then
        assertThat(room, equalTo(roomSeenBy(bob, id, LOBBY, alice, member(alice, false, true))));
    }

    @Test
    void shouldHandHostOver_whenFind_givenSilentHost() {
        // given
        String id = rooms.create(alice);
        rooms.join(id, bob);
        clock.advance(Duration.ofSeconds(30));
        rooms.find(id, bob);
        clock.advance(Duration.ofSeconds(30).plusMillis(1));

        // when
        RoomView room = rooms.find(id, bob);

        // then
        assertThat(room, equalTo(room(id, LOBBY, bob, member(bob, false, true))));
    }

    @Test
    void shouldKeepMember_whenFind_givenHeartbeatsInsteadOfPolls() {
        // given
        String id = rooms.create(alice);
        rooms.join(id, bob);
        clock.advance(Duration.ofSeconds(50));
        rooms.markSeen(alice);
        rooms.markSeen(bob);
        clock.advance(Duration.ofSeconds(50));

        // when
        RoomView room = rooms.find(id, alice);

        // then
        assertThat(room, equalTo(room(id, LOBBY, alice, member(alice, false, true), member(bob, false, false))));
    }

    @Test
    void shouldKeepSilentMembers_whenFind_givenRoomStarting() {
        // given
        String id = startedRoom();
        clock.advance(Duration.ofMinutes(30));

        // when
        RoomView room = rooms.find(id, carol);

        // then
        assertThat(room, equalTo(roomSeenBy(carol, id, STARTING, alice, member(alice, true, true), member(bob, true, false))));
    }

    @Test
    void shouldKeepEmptyRoom_whenFind_givenEmptyFor10Minutes() {
        // given
        String id = rooms.create(alice);
        rooms.leave(id, alice);
        clock.advance(Rules.EMPTY_ROOM_LIFETIME);

        // when
        RoomView room = rooms.find(id, bob);

        // then
        assertThat(room, equalTo(new RoomView(id, LOBBY, null, List.of(), Rules.LEVEL_MIN, Rules.SHAPE_LANDSCAPE,
                Rules.ROOM_SIZE)));
    }

    @Test
    void shouldRemoveRoom_whenFind_givenEmptyForMoreThan10Minutes() {
        // given
        String id = rooms.create(alice);
        rooms.leave(id, alice);
        clock.advance(Rules.EMPTY_ROOM_LIFETIME.plusMillis(1));

        // when
        ApiException exception = assertThrows(ApiException.class, () -> rooms.find(id, bob));

        // then
        assertNotFound(exception);
    }

    @Test
    void shouldRemoveRoom_whenFind_givenAbandonedRoomSilentForElevenMinutes() {
        // given
        String id = rooms.create(alice);
        rooms.join(id, bob);
        clock.advance(JUST_OVER_SILENCE.plus(Rules.EMPTY_ROOM_LIFETIME));

        // when
        ApiException exception = assertThrows(ApiException.class, () -> rooms.find(id, carol));

        // then
        assertNotFound(exception);
    }

    private String fullRoom() {
        String id = rooms.create(alice);
        rooms.join(id, bob);
        rooms.join(id, carol);
        rooms.join(id, dave);
        return id;
    }

    private String startedRoom() {
        String id = rooms.create(alice);
        rooms.join(id, bob);
        rooms.setReady(id, alice, true);
        rooms.setReady(id, bob, true);
        rooms.start(id, alice);
        return id;
    }

    private void fillRooms(int count) {
        for (int i = 0; i < count; i++) {
            rooms.create(player("Filler" + i));
        }
    }

    private static long countAccepted(List<Callable<Boolean>> calls) throws Exception {
        List<Future<Boolean>> results;
        try (ExecutorService executor = Executors.newFixedThreadPool(16)) {
            results = executor.invokeAll(calls);
        }
        long accepted = 0;
        for (Future<Boolean> result : results) {
            accepted += result.get() ? 1 : 0;
        }
        return accepted;
    }

    private static boolean isAccepted(Runnable call) {
        boolean isAccepted = true;
        try {
            call.run();
        } catch (ApiException e) {
            isAccepted = false;
        }
        return isAccepted;
    }

    private static void assertNotFound(ApiException exception) {
        assertError(exception, Response.Status.NOT_FOUND, ApiException.NOT_FOUND);
    }

    private static void assertError(ApiException exception, Response.Status status, String code) {
        assertThat(exception.status(), equalTo(status));
        assertThat(exception.code(), equalTo(code));
    }

    private static LobbyPlayer player(String nick) {
        return new LobbyPlayer(UUID.randomUUID(), nick, Rules.KIND_HUMAN, true);
    }

    private static RoomView.RoomPlayer member(LobbyPlayer player, boolean isReady, boolean isHost) {
        return new RoomView.RoomPlayer(player.nick(), player.kind(), isReady, isHost, false);
    }

    private static RoomView room(String id, String state, LobbyPlayer host, RoomView.RoomPlayer... players) {
        return roomSeenBy(host, id, state, host, players);
    }

    private static RoomView roomSeenBy(LobbyPlayer viewer, String id, String state, LobbyPlayer host,
                                       RoomView.RoomPlayer... players) {
        List<RoomView.RoomPlayer> seenPlayers = Arrays.stream(players)
                .map(player -> viewer.nick().equals(player.nick())
                        ? new RoomView.RoomPlayer(player.nick(), player.kind(), player.isReady(), player.isHost(), true)
                        : player)
                .toList();
        return new RoomView(id, state, new RoomView.Host(host.nick()), seenPlayers, Rules.LEVEL_MIN,
                Rules.SHAPE_LANDSCAPE, Rules.ROOM_SIZE);
    }
}
