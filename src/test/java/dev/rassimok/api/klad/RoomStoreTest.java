package dev.rassimok.api.klad;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import java.util.stream.Stream;

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
    private static final String RACING = "racing";
    private static final Duration JUST_OVER_SILENCE = Rules.LOBBY_MEMBER_SILENCE.plusMillis(1);
    private static final String LEVEL_THREE = "level 3?";

    private final LobbyPlayer alice = player("Alice");
    private final LobbyPlayer bob = player("Bob");
    private final LobbyPlayer carol = player("Carol");
    private final LobbyPlayer dave = player("Dave");
    private final LobbyPlayer eve = player("Eve");
    // Alice first, then Bob, Carol, Dave and more up to the room size; Eve stays outside.
    private final List<LobbyPlayer> fullRoomPlayers = Stream.concat(Stream.of(alice, bob, carol, dave),
            IntStream.range(4, Rules.ROOM_SIZE).mapToObj(i -> player("Member" + i))).toList();
    private final FakeClock clock = new FakeClock(START);
    private final RoomStore rooms = new RoomStore(clock);

    @Test
    void shouldMakeCallerHostAndOnlyPlayer_whenCreate() {
        // when
        String id = rooms.create(alice);

        // then
        assertThat(rooms.find(id, alice), equalTo(room(id, LOBBY, alice, member(alice, 1, false, true))));
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
        assertThat(rooms.find(newId, alice), equalTo(room(newId, LOBBY, alice, member(alice, 1, false, true))));
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
        assertThat(rooms.find(otherId, alice), equalTo(room(otherId, LOBBY, alice, member(alice, 1, false, true))));
        assertThat(rooms.find(newId, bob), equalTo(room(newId, LOBBY, bob, member(bob, 1, false, true))));
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
        assertThat(rooms.find(newId, alice), equalTo(room(newId, LOBBY, alice, member(alice, 1, false, true))));
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
        RoomView.RoomPlayer host = new RoomView.RoomPlayer("Twin", Rules.KIND_HUMAN, false, true, false, 1);
        RoomView.RoomPlayer guest = new RoomView.RoomPlayer("Twin", Rules.KIND_HUMAN, false, false, false, 2);

        // when
        RoomView seenByFirst = rooms.find(id, firstTwin);
        RoomView seenBySecond = rooms.find(id, secondTwin);

        // then
        assertThat(seenByFirst.players(), equalTo(List.of(
                new RoomView.RoomPlayer("Twin", Rules.KIND_HUMAN, false, true, true, 1), guest)));
        assertThat(seenBySecond.players(), equalTo(List.of(
                host, new RoomView.RoomPlayer("Twin", Rules.KIND_HUMAN, false, false, true, 2))));
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
                equalTo(roomSeenBy(bob, id, LOBBY, alice, member(alice, 1, false, true), member(bob, 2, false, false))));
    }

    @Test
    void shouldCreateRoom_whenCreate_givenCapReachedByRoomsAbandonedJustOverElevenMinutesAgo() {
        // given
        fillRooms(Rules.ROOMS_MAX);
        clock.advance(Rules.LOBBY_MEMBER_SILENCE.plus(Rules.EMPTY_ROOM_LIFETIME).plusMillis(1));

        // when
        String id = rooms.create(alice);

        // then
        assertThat(rooms.find(id, alice), equalTo(room(id, LOBBY, alice, member(alice, 1, false, true))));
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
    void shouldShowNoMembers_whenFind_givenPlayerNotInFullRoom() {
        // given
        String id = fullRoom();

        // when
        RoomView room = rooms.find(id, eve);

        // then
        assertThat(room, equalTo(seenFromOutside(id, LOBBY)));
    }

    @Test
    void shouldShowNoMembers_whenFind_givenPlayerNotInStartingRoom() {
        // given
        String id = startedRoom();

        // when
        RoomView room = rooms.find(id, carol);

        // then
        assertThat(room, equalTo(seenFromOutside(id, STARTING)));
    }

    @Test
    void shouldShowNoMembers_whenFind_givenPlayerWithLinkToLinkOnlyRoom() {
        // given
        String id = rooms.create(alice);
        rooms.join(id, bob);
        rooms.setListed(id, alice, false);

        // when
        RoomView room = rooms.find(id, carol);

        // then
        assertThat(room, equalTo(linkOnly(seenFromOutside(id, LOBBY))));
    }

    @Test
    void shouldShowNewNick_whenFind_givenMemberRenamed() {
        // given
        String id = rooms.create(alice);
        LobbyPlayer renamed = new LobbyPlayer(alice.id(), "Alicia", alice.kind(), alice.isGuest());

        // when
        RoomView room = rooms.find(id, renamed);

        // then
        assertThat(room, equalTo(room(id, LOBBY, renamed, member(renamed, 1, false, true))));
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
                member(alice, 1, false, true), member(bob, 2, false, false), member(carol, 3, false, false))));
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
        RoomView.RoomPlayer[] members = fullRoomPlayers.stream()
                .map(player -> member(player, fullRoomPlayers.indexOf(player) + 1, false, alice.equals(player)))
                .toArray(RoomView.RoomPlayer[]::new);
        assertThat(room, equalTo(roomSeenBy(dave, id, LOBBY, alice, members)));
        assertThat(room.players().size(), equalTo(10));
    }

    @Test
    void shouldReturnRoom_whenJoin_givenMemberOfStartingRoom() {
        // given
        String id = startedRoom();

        // when
        RoomView room = rooms.join(id, bob);

        // then
        assertThat(room, equalTo(roomSeenBy(bob, id, STARTING, alice, member(alice, 1, true, true), member(bob, 2, true, false))));
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
                equalTo(roomSeenBy(carol, secondId, LOBBY, bob, member(bob, 1, false, true), member(carol, 2, false, false))));
        assertThat(rooms.find(firstId, alice), equalTo(room(firstId, LOBBY, alice, member(alice, 1, false, true))));
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
                member(eve, 1, false, true), member(frank, 2, false, false))));
    }

    @Test
    void shouldMakeJoinerHost_whenJoin_givenEmptyRoom() {
        // given
        String id = rooms.create(alice);
        rooms.leave(id, alice);

        // when
        RoomView room = rooms.join(id, bob);

        // then
        assertThat(room, equalTo(room(id, LOBBY, bob, member(bob, 2, false, true))));
    }

    @Test
    void shouldAnswerNotFound_whenJoin_givenUnknownId() {
        // when
        ApiException exception = assertThrows(ApiException.class, () -> rooms.join(UNKNOWN_ID, alice));

        // then
        assertNotFound(exception);
    }

    @Test
    void shouldTakeExactlyNineMore_whenJoin_givenTwentyPlayersAtOnce() throws Exception {
        // given
        String id = rooms.create(alice);
        List<Callable<Boolean>> joins = IntStream.range(0, 20)
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
        assertThat(rooms.find(id, alice), equalTo(room(id, LOBBY, alice, member(alice, 1, false, true))));
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
                equalTo(room(id, LOBBY, bob, member(bob, 2, false, true), member(carol, 3, false, false))));
    }

    @Test
    void shouldChangeNothing_whenLeave_givenPlayerNotInRoom() {
        // given
        String id = rooms.create(alice);

        // when
        rooms.leave(id, bob);

        // then
        assertThat(rooms.find(id, alice), equalTo(room(id, LOBBY, alice, member(alice, 1, false, true))));
    }

    @Test
    void shouldShowNoHostAndNoPlayers_whenFind_givenEveryoneLeft() {
        // given
        String id = rooms.create(alice);
        rooms.leave(id, alice);

        // when
        RoomView room = rooms.find(id, alice);

        // then
        assertThat(room, equalTo(new RoomView(id, LOBBY, null, List.of(), Rules.LEVEL_MIN, Rules.SHAPE_LANDSCAPE,
                Rules.ROOM_SIZE, true, List.of())));
    }

    @Test
    void shouldRemovePlayer_whenLeave_givenRoomStarting() {
        // given
        String id = startedRoom();

        // when
        rooms.leave(id, alice);

        // then
        assertThat(rooms.find(id, bob), equalTo(room(id, STARTING, bob, member(bob, 2, true, true))));
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
                equalTo(roomSeenBy(bob, id, LOBBY, bob, member(bob, 2, false, true), member(carol, 3, false, false))));
    }

    @Test
    void shouldRemovePlayerAndHandOverHost_whenForget_givenRoomStarting() {
        // given
        String id = startedRoom();

        // when
        rooms.forget(alice.id());

        // then
        assertThat(rooms.find(id, bob), equalTo(room(id, STARTING, bob, member(bob, 2, true, true))));
    }

    @Test
    void shouldChangeNothing_whenForget_givenPlayerInNoRoom() {
        // given
        String id = rooms.create(alice);

        // when
        rooms.forget(bob.id());

        // then
        assertThat(rooms.find(id, alice), equalTo(room(id, LOBBY, alice, member(alice, 1, false, true))));
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
        assertThat(room, equalTo(roomSeenBy(bob, id, LOBBY, alice, member(alice, 1, false, true), member(bob, 2, true, false))));
    }

    @Test
    void shouldClearReadyMark_whenSetReady_givenReadyPlayer() {
        // given
        String id = rooms.create(alice);
        rooms.setReady(id, alice, true);

        // when
        RoomView room = rooms.setReady(id, alice, false);

        // then
        assertThat(room, equalTo(room(id, LOBBY, alice, member(alice, 1, false, true))));
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
                List.of(new RoomView.RoomPlayer("Alice", Rules.KIND_HUMAN, false, true, true, 1)), Rules.LEVEL_MAX,
                Rules.SHAPE_PORTRAIT, Rules.ROOM_SIZE, true, List.of())));
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
        assertThat(room, equalTo(room(id, STARTING, alice, member(alice, 1, true, true), member(bob, 2, true, false))));
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
        assertThat(room, equalTo(room(id, LOBBY, alice, member(alice, 1, false, true), member(bob, 2, false, false))));
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
        assertThat(room, equalTo(room(id, LOBBY, alice, member(alice, 1, false, true))));
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
        RoomView seenByBob = rooms.find(id, bob);

        // then
        assertThat(seenByBob, equalTo(seenFromOutside(id, LOBBY)));
        assertThat(rooms.find(id, alice), equalTo(room(id, LOBBY, alice, member(alice, 1, false, true))));
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
        assertThat(room, equalTo(room(id, LOBBY, bob, member(bob, 2, false, true))));
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
        assertThat(room, equalTo(room(id, LOBBY, alice, member(alice, 1, false, true), member(bob, 2, false, false))));
    }

    @Test
    void shouldKeepSilentMembers_whenFind_givenRoomStarting() {
        // given
        String id = rooms.create(alice);
        rooms.join(id, bob);
        rooms.setReady(id, bob, true);
        clock.advance(Rules.LOBBY_MEMBER_SILENCE.minusSeconds(1));
        rooms.setReady(id, alice, true);
        rooms.start(id, alice);
        clock.advance(Rules.RACE_CONNECT_WAIT.minusSeconds(1));

        // when
        RoomView room = rooms.find(id, alice);

        // then
        assertThat(room, equalTo(room(id, STARTING, alice, member(alice, 1, true, true), member(bob, 2, true, false))));
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
                Rules.ROOM_SIZE, true, List.of())));
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

    @Test
    void shouldShowHostNickCountAndSettings_whenList_givenRoomWithTwoPlayers() {
        // given
        String id = rooms.create(alice);
        rooms.join(id, bob);
        rooms.chooseLevel(id, alice, Rules.LEVEL_MAX, Rules.SHAPE_PORTRAIT);

        // when
        RoomListView list = rooms.list();

        // then
        assertThat(list, equalTo(new RoomListView(List.of(new RoomListView.ListedRoom(id, LOBBY,
                new RoomView.Host("Alice"), 2, Rules.ROOM_SIZE, Rules.LEVEL_MAX, Rules.SHAPE_PORTRAIT)))));
    }

    @Test
    void shouldListFullRoom_whenList_givenRoomFull() {
        // given
        String id = fullRoom();

        // when
        RoomListView list = rooms.list();

        // then
        assertThat(list, equalTo(roomList(listed(id, LOBBY, alice, Rules.ROOM_SIZE))));
    }

    @Test
    void shouldListStartingRoom_whenList_givenRoomStarting() {
        // given
        String id = startedRoom();

        // when
        RoomListView list = rooms.list();

        // then
        assertThat(list, equalTo(roomList(listed(id, STARTING, alice, 2))));
    }

    @Test
    void shouldListRacingRoom_whenList_givenRoomRacing() {
        // given
        String id = startedRoom();
        rooms.connect(id, alice.id(), new FakeSocket());
        rooms.connect(id, bob.id(), new FakeSocket());
        clock.advance(Rules.RACE_COUNTDOWN);

        // when
        RoomListView list = rooms.list();

        // then
        assertThat(list, equalTo(roomList(listed(id, RACING, alice, 2))));
    }

    @Test
    void shouldLeaveOutEmptyRoom_whenList_givenEveryoneLeft() {
        // given
        String id = rooms.create(alice);
        rooms.leave(id, alice);

        // when
        RoomListView list = rooms.list();

        // then
        assertThat(list, equalTo(roomList()));
    }

    @Test
    void shouldLeaveOutRoom_whenList_givenHostSwitchedToLinkOnly() {
        // given
        String id = rooms.create(alice);
        rooms.setListed(id, alice, false);

        // when
        RoomListView list = rooms.list();

        // then
        assertThat(list, equalTo(roomList()));
    }

    @Test
    void shouldListRoomAgain_whenList_givenHostSwitchedBackToListed() {
        // given
        String id = rooms.create(alice);
        rooms.setListed(id, alice, false);
        rooms.setListed(id, alice, true);

        // when
        RoomListView list = rooms.list();

        // then
        assertThat(list, equalTo(roomList(listed(id, LOBBY, alice, 1))));
    }

    @Test
    void shouldListMostPlayersFirstThenOldest_whenList() {
        // given
        LobbyPlayer frank = player("Frank");
        LobbyPlayer grace = player("Grace");
        String oldestSingleId = rooms.create(alice);
        String pairId = rooms.create(bob);
        rooms.join(pairId, carol);
        String newerSingleId = rooms.create(dave);
        String trioId = rooms.create(eve);
        rooms.join(trioId, frank);
        rooms.join(trioId, grace);

        // when
        RoomListView list = rooms.list();

        // then
        assertThat(list, equalTo(roomList(listed(trioId, LOBBY, eve, 3), listed(pairId, LOBBY, bob, 2),
                listed(oldestSingleId, LOBBY, alice, 1), listed(newerSingleId, LOBBY, dave, 1))));
    }

    @Test
    void shouldListTwentyRooms_whenList_givenMoreListedRooms() {
        // given
        List<LobbyPlayer> hosts = IntStream.range(0, Rules.ROOMS_LISTED_MAX + 5)
                .mapToObj(i -> player("Host" + i))
                .toList();
        List<String> ids = hosts.stream().map(rooms::create).toList();
        rooms.join(ids.getLast(), alice);

        // when
        RoomListView list = rooms.list();

        // then
        List<RoomListView.ListedRoom> expected = Stream.concat(
                        Stream.of(listed(ids.getLast(), LOBBY, hosts.getLast(), 2)),
                        IntStream.range(0, Rules.ROOMS_LISTED_MAX - 1)
                                .mapToObj(i -> listed(ids.get(i), LOBBY, hosts.get(i), 1)))
                .toList();
        assertThat(list, equalTo(new RoomListView(expected)));
    }

    @Test
    void shouldListTwentyRooms_whenList_givenOneHundredFiftyRoomsMadeAtOnce() throws Exception {
        // given
        List<Callable<Boolean>> creates = IntStream.range(0, Rules.ROOMS_MAX + 50)
                .<Callable<Boolean>>mapToObj(i -> () -> isAccepted(() -> rooms.create(player("Host" + i))))
                .toList();
        countAccepted(creates);

        // when
        RoomListView list = rooms.list();

        // then
        assertThat(list.rooms().size(), equalTo(Rules.ROOMS_LISTED_MAX));
    }

    @Test
    void shouldRemoveSilentMember_whenList_givenOnlyListReadsForMoreThan60Seconds() {
        // given
        rooms.create(alice);
        clock.advance(Rules.LOBBY_MEMBER_SILENCE.minusSeconds(1));
        rooms.list();
        clock.advance(Duration.ofSeconds(1).plusMillis(1));

        // when
        RoomListView list = rooms.list();

        // then
        assertThat(list, equalTo(roomList()));
    }

    @Test
    void shouldRemoveEmptyRoom_whenFind_givenListReadDuringItsTenMinutes() {
        // given
        String id = rooms.create(alice);
        rooms.leave(id, alice);
        clock.advance(Rules.EMPTY_ROOM_LIFETIME.minusMinutes(1));
        rooms.list();
        clock.advance(Duration.ofMinutes(1).plusMillis(1));

        // when
        ApiException exception = assertThrows(ApiException.class, () -> rooms.find(id, bob));

        // then
        assertNotFound(exception);
    }

    @Test
    void shouldShowLinkOnly_whenSetListed_givenHost() {
        // given
        String id = rooms.create(alice);

        // when
        RoomView room = rooms.setListed(id, alice, false);

        // then
        assertThat(room, equalTo(linkOnly(room(id, LOBBY, alice, member(alice, 1, false, true)))));
    }

    @Test
    void shouldRefuseAndStayListed_whenSetListed_givenMemberWhoIsNotHost() {
        // given
        String id = rooms.create(alice);
        rooms.join(id, bob);

        // when
        ApiException exception = assertThrows(ApiException.class, () -> rooms.setListed(id, bob, false));

        // then
        assertError(exception, Response.Status.FORBIDDEN, ApiException.NOT_HOST);
        assertThat(rooms.list(), equalTo(roomList(listed(id, LOBBY, alice, 2))));
    }

    @Test
    void shouldRefuse_whenSetListed_givenPlayerNotInRoom() {
        // given
        String id = rooms.create(alice);

        // when
        ApiException exception = assertThrows(ApiException.class, () -> rooms.setListed(id, bob, false));

        // then
        assertError(exception, Response.Status.FORBIDDEN, ApiException.NOT_HOST);
    }

    @Test
    void shouldAnswerNotFound_whenSetListed_givenUnknownId() {
        // when
        ApiException exception = assertThrows(ApiException.class, () -> rooms.setListed(UNKNOWN_ID, alice, false));

        // then
        assertNotFound(exception);
    }

    @Test
    void shouldSwitchToLinkOnly_whenSetListed_givenRoomStarting() {
        // given
        String id = startedRoom();

        // when
        RoomView room = rooms.setListed(id, alice, false);

        // then
        assertThat(room, equalTo(linkOnly(room(id, STARTING, alice, member(alice, 1, true, true), member(bob, 2, true, false)))));
        assertThat(rooms.list(), equalTo(roomList()));
    }

    @Test
    void shouldStayLinkOnly_whenLeave_givenHostOfLinkOnlyRoomLeaves() {
        // given
        String id = rooms.create(alice);
        rooms.join(id, bob);
        rooms.setListed(id, alice, false);

        // when
        rooms.leave(id, alice);

        // then
        assertThat(rooms.find(id, bob), equalTo(linkOnly(room(id, LOBBY, bob, member(bob, 2, false, true)))));
    }

    @Test
    void shouldMakeLinkOnlyRoom_whenCreate_givenNotListed() {
        // when
        String id = rooms.create(alice, false);

        // then
        assertThat(rooms.find(id, alice), equalTo(linkOnly(room(id, LOBBY, alice, member(alice, 1, false, true)))));
        assertThat(rooms.list(), equalTo(roomList()));
    }

    @Test
    void shouldLeaveOutRoom_whenList_givenRoomCreatedLinkOnlyAndJoined() {
        // given
        String id = rooms.create(alice, false);
        rooms.join(id, bob);

        // when
        RoomListView list = rooms.list();

        // then
        assertThat(list, equalTo(roomList()));
    }

    @Test
    void shouldListRoom_whenSetListed_givenRoomCreatedLinkOnly() {
        // given
        String id = rooms.create(alice, false);

        // when
        rooms.setListed(id, alice, true);

        // then
        assertThat(rooms.list(), equalTo(roomList(listed(id, LOBBY, alice, 1))));
    }

    @Test
    void shouldKeepMessageWithSenderMarks_whenSend_givenMember() {
        // given
        String id = rooms.create(alice);
        rooms.join(id, bob);

        // when
        RoomView.Message sent = rooms.send(id, bob, LEVEL_THREE);

        // then
        assertThat(sent, equalTo(message(1, bob, 2, true, LEVEL_THREE)));
        assertThat(rooms.find(id, alice), equalTo(withMessages(
                room(id, LOBBY, alice, member(alice, 1, false, true), member(bob, 2, false, false)),
                message(1, bob, 2, false, LEVEL_THREE))));
        assertThat(rooms.find(id, bob), equalTo(withMessages(
                roomSeenBy(bob, id, LOBBY, alice, member(alice, 1, false, true), member(bob, 2, false, false)),
                message(1, bob, 2, true, LEVEL_THREE))));
    }

    @Test
    void shouldShowAccountAndKindOfSender_whenFind_givenAgentAccountSent() {
        // given
        LobbyPlayer agent = new LobbyPlayer(UUID.randomUUID(), "Robot", Rules.KIND_AGENT, false);
        String id = rooms.create(alice);
        rooms.join(id, agent);
        rooms.send(id, agent, LEVEL_THREE);

        // when
        RoomView room = rooms.find(id, alice);

        // then
        assertThat(room.messages(), equalTo(List.of(
                new RoomView.Message(1, 2, "Robot", Rules.KIND_AGENT, false, false, LEVEL_THREE))));
    }

    @Test
    void shouldTellTwinsApart_whenFind_givenTwoGuestsWithSameNickSent() {
        // given
        LobbyPlayer firstTwin = player("Twin");
        LobbyPlayer secondTwin = player("Twin");
        String id = rooms.create(firstTwin);
        rooms.join(id, secondTwin);
        rooms.send(id, firstTwin, "first");
        rooms.send(id, secondTwin, "second");

        // when
        RoomView room = rooms.find(id, secondTwin);

        // then
        assertThat(room.messages(), equalTo(List.of(
                message(1, firstTwin, 1, false, "first"),
                message(2, secondTwin, 2, true, "second"))));
    }

    @Test
    void shouldKeepNickOfSending_whenFind_givenSenderRenamedLater() {
        // given
        String id = rooms.create(alice);
        rooms.send(id, alice, "before");
        LobbyPlayer renamed = new LobbyPlayer(alice.id(), "Alicia", alice.kind(), alice.isGuest());
        rooms.send(id, renamed, "after");

        // when
        RoomView room = rooms.find(id, renamed);

        // then
        assertThat(room.messages(), equalTo(List.of(
                message(1, alice, 1, true, "before"),
                message(2, renamed, 1, true, "after"))));
    }

    @Test
    void shouldAnswerNotFound_whenSend_givenPlayerNotInRoom() {
        // given
        String id = rooms.create(alice);

        // when
        ApiException exception = assertThrows(ApiException.class, () -> rooms.send(id, bob, LEVEL_THREE));

        // then
        assertNotFound(exception);
        assertThat(rooms.find(id, alice), equalTo(room(id, LOBBY, alice, member(alice, 1, false, true))));
    }

    @Test
    void shouldAnswerNotFound_whenSend_givenMemberWhoLeft() {
        // given
        String id = rooms.create(alice);
        rooms.join(id, bob);
        rooms.leave(id, bob);

        // when
        ApiException exception = assertThrows(ApiException.class, () -> rooms.send(id, bob, LEVEL_THREE));

        // then
        assertNotFound(exception);
    }

    @Test
    void shouldAnswerNotFound_whenSend_givenUnknownId() {
        // when
        ApiException exception = assertThrows(ApiException.class, () -> rooms.send(UNKNOWN_ID, alice, LEVEL_THREE));

        // then
        assertNotFound(exception);
    }

    @Test
    void shouldShowNoMessages_whenFind_givenPlayerNotInRoom() {
        // given
        String id = rooms.create(alice);
        rooms.join(id, bob);
        rooms.send(id, alice, LEVEL_THREE);
        rooms.send(id, bob, "yes");

        // when
        RoomView room = rooms.find(id, carol);

        // then
        assertThat(room, equalTo(seenFromOutside(id, LOBBY)));
    }

    @Test
    void shouldShowNoMessages_whenFind_givenMemberWhoLeft() {
        // given
        String id = rooms.create(alice);
        rooms.join(id, bob);
        rooms.send(id, bob, LEVEL_THREE);
        rooms.leave(id, bob);
        rooms.send(id, alice, "bye");

        // when
        RoomView room = rooms.find(id, bob);

        // then
        assertThat(room, equalTo(seenFromOutside(id, LOBBY)));
    }

    @Test
    void shouldShowOnlyLaterMessages_whenFind_givenPlayerJoinedAfterThem() {
        // given
        String id = rooms.create(alice);
        rooms.send(id, alice, "one");
        rooms.send(id, alice, "two");
        rooms.join(id, bob);
        rooms.send(id, alice, "three");

        // when
        RoomView seenByBob = rooms.find(id, bob);
        RoomView seenByAlice = rooms.find(id, alice);

        // then
        assertThat(seenByBob, equalTo(withMessages(
                roomSeenBy(bob, id, LOBBY, alice, member(alice, 1, false, true), member(bob, 2, false, false)),
                message(3, alice, 1, false, "three"))));
        assertThat(seenByAlice.messages(), equalTo(List.of(
                message(1, alice, 1, true, "one"),
                message(2, alice, 1, true, "two"),
                message(3, alice, 1, true, "three"))));
    }

    @Test
    void shouldGiveNewNumberAndOnlyLaterMessages_whenJoin_givenMemberLeftAndCameBack() {
        // given
        String id = rooms.create(alice);
        rooms.join(id, bob);
        rooms.send(id, bob, "first visit");
        rooms.leave(id, bob);
        rooms.send(id, alice, "while away");

        // when
        RoomView joined = rooms.join(id, bob);
        rooms.send(id, bob, "back");

        // then
        assertThat(joined, equalTo(
                roomSeenBy(bob, id, LOBBY, alice, member(alice, 1, false, true), member(bob, 3, false, false))));
        assertThat(rooms.find(id, bob).messages(), equalTo(List.of(message(3, bob, 3, true, "back"))));
        assertThat(rooms.find(id, alice).messages(), equalTo(List.of(
                message(1, bob, 2, false, "first visit"),
                message(2, alice, 1, true, "while away"),
                message(3, bob, 3, false, "back"))));
    }

    @Test
    void shouldShowOnlyNewerMessages_whenFind_givenAfter() {
        // given
        String id = rooms.create(alice);
        rooms.send(id, alice, "one");
        rooms.send(id, alice, "two");
        rooms.send(id, alice, "three");

        // when
        RoomView afterTwo = rooms.find(id, alice, 2);
        RoomView afterThree = rooms.find(id, alice, 3);
        RoomView afterNone = rooms.find(id, alice, 0);

        // then
        assertThat(afterTwo, equalTo(withMessages(room(id, LOBBY, alice, member(alice, 1, false, true)),
                message(3, alice, 1, true, "three"))));
        assertThat(afterThree.messages(), equalTo(List.of()));
        assertThat(afterNone.messages().stream().map(RoomView.Message::n).toList(), equalTo(List.of(1, 2, 3)));
    }

    @Test
    void shouldShowOnlyMessagesAfterJoin_whenFind_givenAfterBeforeJoin() {
        // given
        String id = rooms.create(alice);
        rooms.send(id, alice, "one");
        rooms.send(id, alice, "two");
        rooms.join(id, bob);
        rooms.send(id, alice, "three");

        // when
        RoomView room = rooms.find(id, bob, 1);

        // then
        assertThat(room.messages(), equalTo(List.of(message(3, alice, 1, false, "three"))));
    }

    @Test
    void shouldShowAllMessages_whenJoinReadyLevelListedOrStart_givenMember() {
        // given
        String id = rooms.create(alice);
        rooms.join(id, bob);
        rooms.send(id, bob, LEVEL_THREE);
        List<RoomView.Message> expected = List.of(message(1, bob, 2, false, LEVEL_THREE));

        // when
        RoomView ready = rooms.setReady(id, alice, true);
        RoomView level = rooms.chooseLevel(id, alice, Rules.LEVEL_MAX, Rules.SHAPE_PORTRAIT);
        RoomView listed = rooms.setListed(id, alice, false);
        RoomView joinedAgain = rooms.join(id, alice);
        rooms.setReady(id, bob, true);
        RoomView started = rooms.start(id, alice);

        // then
        assertThat(ready.messages(), equalTo(expected));
        assertThat(level.messages(), equalTo(expected));
        assertThat(listed.messages(), equalTo(expected));
        assertThat(joinedAgain.messages(), equalTo(expected));
        assertThat(started.messages(), equalTo(expected));
    }

    @Test
    void shouldDropOldest_whenSend_givenThirtyFirstMessage() {
        // given
        String id = fullRoom();
        for (int i = 1; i <= Rules.CHAT_MESSAGES_KEPT; i++) {
            rooms.send(id, fullRoomPlayers.get(i % Rules.ROOM_SIZE), "message " + i);
        }

        // when
        rooms.send(id, fullRoomPlayers.get(1), "message 31");

        // then
        List<RoomView.Message> expected = IntStream.rangeClosed(2, Rules.CHAT_MESSAGES_KEPT + 1)
                .mapToObj(i -> {
                    int senderIndex = i % Rules.ROOM_SIZE;
                    return message(i, fullRoomPlayers.get(senderIndex), senderIndex + 1, 0 == senderIndex,
                            "message " + i);
                })
                .toList();
        assertThat(rooms.find(id, alice).messages(), equalTo(expected));
    }

    @Test
    void shouldKeepThirtyMessages_whenFind_givenThirtyMessages() {
        // given
        String id = fullRoom();
        for (int i = 1; i <= Rules.CHAT_MESSAGES_KEPT; i++) {
            rooms.send(id, fullRoomPlayers.get(i % Rules.ROOM_SIZE), "message " + i);
        }

        // when
        RoomView room = rooms.find(id, alice);

        // then
        assertThat(room.messages().stream().map(RoomView.Message::n).toList(),
                equalTo(IntStream.rangeClosed(1, Rules.CHAT_MESSAGES_KEPT).boxed().toList()));
    }

    @Test
    void shouldStartWithoutMessages_whenCreate_givenHostSentInOldRoom() {
        // given
        String oldId = rooms.create(alice);
        rooms.join(oldId, bob);
        rooms.send(oldId, alice, LEVEL_THREE);

        // when
        String newId = rooms.create(alice);

        // then
        assertNotFound(assertThrows(ApiException.class, () -> rooms.find(oldId, bob)));
        assertThat(rooms.find(newId, alice), equalTo(room(newId, LOBBY, alice, member(alice, 1, false, true))));
    }

    @Test
    void shouldTakeMessagesAlong_whenFind_givenRoomEmptyForMoreThan10Minutes() {
        // given
        String id = rooms.create(alice);
        rooms.send(id, alice, LEVEL_THREE);
        rooms.leave(id, alice);
        clock.advance(Rules.EMPTY_ROOM_LIFETIME.plusMillis(1));

        // when
        ApiException exception = assertThrows(ApiException.class, () -> rooms.find(id, alice));

        // then
        assertNotFound(exception);
    }

    @Test
    void shouldSendMessage_whenSend_givenRoomStarting() {
        // given
        String id = startedRoom();

        // when
        RoomView.Message sent = rooms.send(id, bob, LEVEL_THREE);

        // then
        assertThat(sent, equalTo(message(1, bob, 2, true, LEVEL_THREE)));
        assertThat(rooms.find(id, alice).messages(), equalTo(List.of(message(1, bob, 2, false, LEVEL_THREE))));
    }

    @Test
    void shouldRefuseAndKeepNothing_whenSend_givenSixthMessageWithinTenSeconds() {
        // given
        String id = rooms.create(alice);
        sendFive(id, alice);
        clock.advance(Rules.CHAT_WINDOW.minusMillis(1));

        // when
        ApiException exception = assertThrows(ApiException.class, () -> rooms.send(id, alice, "sixth"));

        // then
        assertError(exception, Response.Status.TOO_MANY_REQUESTS, ApiException.RATE_LIMITED);
        assertThat(rooms.find(id, alice).messages().stream().map(RoomView.Message::text).toList(),
                equalTo(List.of("message 1", "message 2", "message 3", "message 4", "message 5")));
    }

    @Test
    void shouldAccept_whenSend_givenSixthMessageTenSecondsAfterFirstFive() {
        // given
        String id = rooms.create(alice);
        sendFive(id, alice);
        clock.advance(Rules.CHAT_WINDOW);

        // when
        RoomView.Message sent = rooms.send(id, alice, "sixth");

        // then
        assertThat(sent, equalTo(message(6, alice, 1, true, "sixth")));
    }

    @Test
    void shouldNotCountRefusedMessage_whenSend_givenRefusedMessageInWindow() {
        // given
        String id = rooms.create(alice);
        rooms.send(id, alice, "first");
        clock.advance(Duration.ofSeconds(5));
        for (int i = 0; i < Rules.CHAT_MESSAGES_PER_WINDOW - 1; i++) {
            rooms.send(id, alice, "later");
        }
        clock.advance(Duration.ofSeconds(4));
        assertThrows(ApiException.class, () -> rooms.send(id, alice, "refused"));
        clock.advance(Duration.ofSeconds(1));

        // when
        RoomView.Message sent = rooms.send(id, alice, "accepted");

        // then
        assertThat(sent, equalTo(message(6, alice, 1, true, "accepted")));
    }

    @Test
    void shouldStillRefuse_whenSend_givenSenderLeftAndJoinedAgain() {
        // given
        String id = rooms.create(alice);
        rooms.join(id, bob);
        sendFive(id, bob);
        rooms.leave(id, bob);
        rooms.join(id, bob);

        // when
        ApiException exception = assertThrows(ApiException.class, () -> rooms.send(id, bob, "sixth"));

        // then
        assertError(exception, Response.Status.TOO_MANY_REQUESTS, ApiException.RATE_LIMITED);
    }

    @Test
    void shouldCountEachMemberApart_whenSend_givenOtherMemberAtLimit() {
        // given
        String id = rooms.create(alice);
        rooms.join(id, bob);
        sendFive(id, alice);

        // when
        RoomView.Message sent = rooms.send(id, bob, LEVEL_THREE);

        // then
        assertThat(sent, equalTo(message(6, bob, 2, true, LEVEL_THREE)));
    }

    @Test
    void shouldKeepSender_whenFind_givenSenderOnlySentForMoreThan60Seconds() {
        // given
        String id = rooms.create(alice);
        rooms.join(id, bob);
        clock.advance(Duration.ofSeconds(50));
        rooms.find(id, alice);
        rooms.send(id, bob, LEVEL_THREE);
        clock.advance(Duration.ofSeconds(50));

        // when
        RoomView room = rooms.find(id, alice);

        // then
        assertThat(room, equalTo(withMessages(
                room(id, LOBBY, alice, member(alice, 1, false, true), member(bob, 2, false, false)),
                message(1, bob, 2, false, LEVEL_THREE))));
    }

    @Test
    void shouldNumberInOrderWithoutGaps_whenSend_givenEveryMemberAtOnce() throws Exception {
        // given
        String id = fullRoom();
        List<Callable<Integer>> sends = fullRoomPlayers.stream()
                .flatMap(player -> IntStream.range(0, Rules.CHAT_MESSAGES_PER_WINDOW)
                        .<Callable<Integer>>mapToObj(i -> () -> rooms.send(id, player, player.nick() + " " + i).n()))
                .toList();

        // when
        List<Integer> numbers = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(16)) {
            for (Future<Integer> number : executor.invokeAll(sends)) {
                numbers.add(number.get());
            }
        }

        // then
        int sent = Rules.ROOM_SIZE * Rules.CHAT_MESSAGES_PER_WINDOW;
        assertThat(numbers.stream().sorted().toList(), equalTo(IntStream.rangeClosed(1, sent).boxed().toList()));
        assertThat(rooms.find(id, alice).messages().stream().map(RoomView.Message::n).toList(),
                equalTo(IntStream.rangeClosed(sent - Rules.CHAT_MESSAGES_KEPT + 1, sent).boxed().toList()));
    }

    @Test
    void shouldRemoveOnlyTheirMessages_whenForgetDeleted_givenMember() {
        // given
        String id = rooms.create(alice);
        rooms.join(id, bob);
        rooms.join(id, carol);
        rooms.send(id, bob, "one");
        rooms.send(id, alice, "two");
        rooms.send(id, bob, "three");
        rooms.send(id, carol, "four");

        // when
        rooms.forgetDeleted(bob.id());

        // then
        assertThat(rooms.find(id, alice), equalTo(withMessages(
                room(id, LOBBY, alice, member(alice, 1, false, true), member(carol, 3, false, false)),
                message(2, alice, 1, true, "two"),
                message(4, carol, 3, false, "four"))));
    }

    @Test
    void shouldRemoveTheirMessages_whenForgetDeleted_givenPlayerLeftRoomEarlier() {
        // given
        String id = rooms.create(alice);
        rooms.join(id, bob);
        rooms.send(id, bob, "one");
        rooms.send(id, alice, "two");
        rooms.leave(id, bob);

        // when
        rooms.forgetDeleted(bob.id());

        // then
        assertThat(rooms.find(id, alice), equalTo(withMessages(
                room(id, LOBBY, alice, member(alice, 1, false, true)),
                message(2, alice, 1, true, "two"))));
    }

    @Test
    void shouldRemoveTheirMessagesFromBothRooms_whenForgetDeleted_givenPlayerMovedToOtherRoom() {
        // given
        String firstId = rooms.create(alice);
        rooms.join(firstId, bob);
        rooms.send(firstId, bob, "in the first");
        rooms.send(firstId, alice, "stays in the first");
        String secondId = rooms.create(carol);
        rooms.join(secondId, bob);
        rooms.send(secondId, carol, "stays in the second");
        rooms.send(secondId, bob, "in the second");

        // when
        rooms.forgetDeleted(bob.id());

        // then
        assertThat(rooms.find(firstId, alice), equalTo(withMessages(
                room(firstId, LOBBY, alice, member(alice, 1, false, true)),
                message(2, alice, 1, true, "stays in the first"))));
        assertThat(rooms.find(secondId, carol), equalTo(withMessages(
                room(secondId, LOBBY, carol, member(carol, 1, false, true)),
                message(1, carol, 1, true, "stays in the second"))));
    }

    @Test
    void shouldRemoveTheirMessages_whenForgetDeleted_givenPlayerRemovedAfterSilence() {
        // given
        String id = rooms.create(alice);
        rooms.join(id, bob);
        rooms.send(id, bob, "one");
        rooms.send(id, alice, "two");
        clock.advance(Duration.ofSeconds(30));
        rooms.find(id, alice);
        clock.advance(Duration.ofSeconds(30).plusMillis(1));
        rooms.find(id, alice);

        // when
        rooms.forgetDeleted(bob.id());

        // then
        assertThat(rooms.find(id, alice), equalTo(withMessages(
                room(id, LOBBY, alice, member(alice, 1, false, true)),
                message(2, alice, 1, true, "two"))));
    }

    @Test
    void shouldRemoveMessagesUnderEveryNumber_whenForgetDeleted_givenPlayerLeftAndCameBack() {
        // given
        String id = rooms.create(alice);
        rooms.join(id, bob);
        rooms.send(id, bob, "first visit");
        rooms.leave(id, bob);
        rooms.send(id, alice, "while away");
        rooms.join(id, bob);
        rooms.send(id, bob, "back");

        // when
        rooms.forgetDeleted(bob.id());

        // then
        assertThat(rooms.find(id, alice), equalTo(withMessages(
                room(id, LOBBY, alice, member(alice, 1, false, true)),
                message(2, alice, 1, true, "while away"))));
    }

    @Test
    void shouldGiveNextNumber_whenSend_givenDeletedPlayersLastMessagesRemoved() {
        // given
        String id = rooms.create(alice);
        rooms.join(id, bob);
        rooms.send(id, alice, "one");
        rooms.send(id, bob, "two");
        rooms.send(id, bob, "three");
        rooms.forgetDeleted(bob.id());

        // when
        RoomView.Message sent = rooms.send(id, alice, "four");

        // then
        assertThat(sent, equalTo(message(4, alice, 1, true, "four")));
        assertThat(rooms.find(id, alice), equalTo(withMessages(
                room(id, LOBBY, alice, member(alice, 1, false, true)),
                message(1, alice, 1, true, "one"),
                message(4, alice, 1, true, "four"))));
    }

    @Test
    void shouldKeepTheirMessages_whenForget_givenMemberSent() {
        // given
        String id = rooms.create(alice);
        rooms.join(id, bob);
        rooms.send(id, bob, "one");
        rooms.send(id, alice, "two");

        // when
        rooms.forget(bob.id());

        // then
        assertThat(rooms.find(id, alice), equalTo(withMessages(
                room(id, LOBBY, alice, member(alice, 1, false, true)),
                message(1, bob, 2, false, "one"),
                message(2, alice, 1, true, "two"))));
    }

    private String fullRoom() {
        String id = rooms.create(alice);
        fullRoomPlayers.stream().skip(1).forEach(player -> rooms.join(id, player));
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

    private void sendFive(String id, LobbyPlayer sender) {
        for (int i = 1; i <= Rules.CHAT_MESSAGES_PER_WINDOW; i++) {
            rooms.send(id, sender, "message " + i);
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

    private static RoomView.RoomPlayer member(LobbyPlayer player, int number, boolean isReady, boolean isHost) {
        return new RoomView.RoomPlayer(player.nick(), player.kind(), isReady, isHost, false, number);
    }

    private static RoomView room(String id, String state, LobbyPlayer host, RoomView.RoomPlayer... players) {
        return roomSeenBy(host, id, state, host, players);
    }

    private static RoomView roomSeenBy(LobbyPlayer viewer, String id, String state, LobbyPlayer host,
                                       RoomView.RoomPlayer... players) {
        List<RoomView.RoomPlayer> seenPlayers = Arrays.stream(players)
                .map(player -> viewer.nick().equals(player.nick())
                        ? new RoomView.RoomPlayer(player.nick(), player.kind(), player.isReady(), player.isHost(), true,
                        player.member())
                        : player)
                .toList();
        return new RoomView(id, state, new RoomView.Host(host.nick()), seenPlayers, Rules.LEVEL_MIN,
                Rules.SHAPE_LANDSCAPE, Rules.ROOM_SIZE, true, List.of());
    }

    private static RoomView seenFromOutside(String id, String state) {
        return new RoomView(id, state, null, List.of(), Rules.LEVEL_MIN, Rules.SHAPE_LANDSCAPE, Rules.ROOM_SIZE, true,
                List.of());
    }

    private static RoomView linkOnly(RoomView room) {
        return new RoomView(room.id(), room.state(), room.host(), room.players(), room.level(), room.shape(),
                room.size(), false, room.messages());
    }

    private static RoomView.Message message(int n, LobbyPlayer sender, int member, boolean isYou, String text) {
        return new RoomView.Message(n, member, sender.nick(), sender.kind(), sender.isGuest(), isYou, text);
    }

    private static RoomView withMessages(RoomView room, RoomView.Message... messages) {
        return new RoomView(room.id(), room.state(), room.host(), room.players(), room.level(), room.shape(),
                room.size(), room.isListed(), List.of(messages));
    }

    private static RoomListView roomList(RoomListView.ListedRoom... listed) {
        return new RoomListView(List.of(listed));
    }

    private static RoomListView.ListedRoom listed(String id, String state, LobbyPlayer host, int players) {
        return new RoomListView.ListedRoom(id, state, new RoomView.Host(host.nick()), players, Rules.ROOM_SIZE,
                Rules.LEVEL_MIN, Rules.SHAPE_LANDSCAPE);
    }
}
