package dev.rassimok.api.klad;

import io.vertx.core.json.JsonObject;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The race of a room, driven through RoomStore with a fake clock and fake sockets. */
class RoomStoreRaceTest {

    private static final Instant START = Instant.parse("2026-11-13T12:00:00Z");
    private static final Duration MILLISECOND = Duration.ofMillis(1);
    private static final String LOBBY = "lobby";
    private static final String STARTING = "starting";
    private static final String RACING = "racing";
    private static final JsonObject COUNTDOWN = json("{'type':'countdown','seconds':3}");
    private static final JsonObject PONG = json("{'type':'pong'}");

    private final LobbyPlayer alice = player("Alice");
    private final LobbyPlayer bob = player("Bob");
    private final LobbyPlayer carol = player("Carol");
    private final FakeClock clock = new FakeClock(START);
    private final RoomStore rooms = new RoomStore(clock);

    @Test
    void shouldRefuseWithNotFound_whenRaceRefusal_givenUnknownRoom() {
        // when
        int status = rooms.raceRefusal("AAAAAAAAAA", alice.id());

        // then
        assertThat(status, equalTo(404));
    }

    @Test
    void shouldRefuseWithConflict_whenRaceRefusal_givenPlayerNotInRoom() {
        // given
        String id = startedRoom(alice, bob);

        // when
        int status = rooms.raceRefusal(id, carol.id());

        // then
        assertThat(status, equalTo(409));
    }

    @Test
    void shouldRefuseWithConflict_whenRaceRefusal_givenRoomInLobby() {
        // given
        String id = rooms.create(alice);
        rooms.join(id, bob);

        // when
        int status = rooms.raceRefusal(id, alice.id());

        // then
        assertThat(status, equalTo(409));
    }

    @Test
    void shouldAccept_whenRaceRefusal_givenMemberOfStartingRoom() {
        // given
        String id = startedRoom(alice, bob);

        // when
        int status = rooms.raceRefusal(id, bob.id());

        // then
        assertThat(status, equalTo(SocketEndpoint.NO_REFUSAL));
    }

    @Test
    void shouldAccept_whenRaceRefusal_givenMemberOfRacingRoom() {
        // given
        Race race = racingRoom();

        // when
        int status = rooms.raceRefusal(race.id(), bob.id());

        // then
        assertThat(status, equalTo(SocketEndpoint.NO_REFUSAL));
    }

    @Test
    void shouldSendState_whenConnect_givenStartingRoom() {
        // given
        String id = startedRoom(alice, bob);

        // when
        FakeSocket socket = connect(id, alice);

        // then
        assertThat(socket.messages, equalTo(List.of(json("""
                {'type':'state','state':'starting','level':1,'shape':'landscape','players':[
                {'slot':0,'nick':'Alice','kind':'human','you':true,'connected':true,'finished':false},
                {'slot':1,'nick':'Bob','kind':'human','you':false,'connected':false,'finished':false}],
                'positions':[]}"""))));
        assertThat(socket.closeReason, nullValue());
    }

    @Test
    void shouldCloseSocket_whenConnect_givenRoomBackInLobbySinceHandshakeCheck() {
        // given
        String id = rooms.create(alice);
        rooms.join(id, bob);

        // when
        FakeSocket socket = connect(id, alice);

        // then
        assertThat(socket.messages, empty());
        assertThat(socket.closeReason, equalTo(Room.RACE_OVER));
    }

    @Test
    void shouldCloseSocket_whenConnect_givenRoomGoneSinceHandshakeCheck() {
        // given
        String id = startedRoom(alice, bob);
        rooms.create(alice);

        // when
        FakeSocket socket = connect(id, bob);

        // then
        assertThat(socket.messages, empty());
        assertThat(socket.closeReason, equalTo(Room.RACE_OVER));
    }

    @Test
    void shouldCloseOldSocketAsReplaced_whenConnect_givenMemberAlreadyConnected() {
        // given
        String id = startedRoom(alice, bob);
        FakeSocket oldSocket = connect(id, alice);

        // when
        FakeSocket newSocket = connect(id, alice);

        // then
        assertThat(oldSocket.closeReason, equalTo(Room.REPLACED));
        assertThat(newSocket.closeReason, nullValue());
        assertThat(newSocket.messages.size(), equalTo(1));
    }

    @Test
    void shouldKeepNewSocket_whenDisconnect_givenReplacedSocketClosesLate() {
        // given
        String id = startedRoom(alice, bob);
        FakeSocket oldSocket = connect(id, alice);
        FakeSocket newSocket = connect(id, alice);

        // when
        rooms.disconnect(id, alice.id(), oldSocket);
        rooms.receive(id, alice.id(), newSocket, new RaceMessage.Ping());

        // then
        assertThat(newSocket.messages.getLast(), equalTo(PONG));
    }

    @Test
    void shouldIgnoreMessage_whenReceive_givenReplacedSocket() {
        // given
        String id = startedRoom(alice, bob);
        FakeSocket oldSocket = connect(id, alice);
        connect(id, alice);
        oldSocket.messages.clear();

        // when
        rooms.receive(id, alice.id(), oldSocket, new RaceMessage.Ping());

        // then
        assertThat(oldSocket.messages, empty());
    }

    @Test
    void shouldSendNoCountdown_whenConnect_givenMemberNotYetConnected() {
        // given
        String id = startedRoom(alice, bob);

        // when
        FakeSocket socket = connect(id, alice);

        // then
        assertThat(types(socket), equalTo(List.of("state")));
    }

    @Test
    void shouldSendCountdownToEveryone_whenConnect_givenLastMemberConnects() {
        // given
        String id = startedRoom(alice, bob, carol);
        FakeSocket aliceSocket = connect(id, alice);
        FakeSocket bobSocket = connect(id, bob);

        // when
        FakeSocket carolSocket = connect(id, carol);

        // then
        assertThat(aliceSocket.messages.getLast(), equalTo(COUNTDOWN));
        assertThat(bobSocket.messages.getLast(), equalTo(COUNTDOWN));
        assertThat(types(carolSocket), equalTo(List.of("state", "countdown")));
        assertThat(rooms.find(id, alice).state(), equalTo(STARTING));
    }

    @Test
    void shouldSendNoGo_whenAdvance_givenJustUnderThreeSecondsAfterCountdown() {
        // given
        String id = startedRoom(alice, bob);
        FakeSocket aliceSocket = connect(id, alice);
        connect(id, bob);
        clock.advance(Rules.RACE_COUNTDOWN.minus(MILLISECOND));

        // when
        rooms.advance();

        // then
        assertThat(types(aliceSocket), equalTo(List.of("state", "countdown")));
        assertThat(rooms.find(id, alice).state(), equalTo(STARTING));
    }

    @Test
    void shouldSendGoWithOneServerTimeToEveryone_whenAdvance_givenThreeSecondsAfterCountdown() {
        // given
        String id = startedRoom(alice, bob);
        FakeSocket aliceSocket = connect(id, alice);
        FakeSocket bobSocket = connect(id, bob);
        clock.advance(Rules.RACE_COUNTDOWN);

        // when
        rooms.advance();

        // then
        JsonObject go = json("{'type':'go'}").put("at", START.plus(Rules.RACE_COUNTDOWN).toEpochMilli());
        assertThat(aliceSocket.messages.getLast(), equalTo(go));
        assertThat(bobSocket.messages.getLast(), equalTo(go));
        assertThat(rooms.find(id, alice).state(), equalTo(RACING));
    }

    @Test
    void shouldWaitForEveryMember_whenAdvance_givenJustUnderFifteenSecondsAfterStarting() {
        // given
        String id = startedRoom(alice, bob);
        FakeSocket aliceSocket = connect(id, alice);
        clock.advance(Rules.RACE_CONNECT_WAIT.minus(MILLISECOND));

        // when
        rooms.advance();

        // then
        assertThat(types(aliceSocket), equalTo(List.of("state")));
        assertThat(rooms.find(id, alice).players().size(), equalTo(2));
    }

    @Test
    void shouldStartCountdownAndCountMissingMemberAsLeft_whenAdvance_givenFifteenSecondsAfterStarting() {
        // given
        String id = startedRoom(alice, bob, carol);
        FakeSocket aliceSocket = connect(id, alice);
        FakeSocket carolSocket = connect(id, carol);
        clock.advance(Rules.RACE_CONNECT_WAIT);

        // when
        rooms.advance();

        // then
        assertThat(aliceSocket.messages.getLast(), equalTo(COUNTDOWN));
        assertThat(carolSocket.messages.getLast(), equalTo(COUNTDOWN));
        assertThat(rooms.find(id, alice), equalTo(new RoomView(id, STARTING, new RoomView.Host("Alice"),
                List.of(new RoomView.RoomPlayer("Alice", Rules.KIND_HUMAN, true, true, true),
                        new RoomView.RoomPlayer("Carol", Rules.KIND_HUMAN, true, false, false)),
                Rules.LEVEL_MIN, Rules.SHAPE_LANDSCAPE, Rules.ROOM_SIZE)));
        assertThat(rooms.raceRefusal(id, bob.id()), equalTo(409));
    }

    @Test
    void shouldListMissingMemberAsLeft_whenRaceEnds_givenMemberNeverConnected() {
        // given
        String id = startedRoom(alice, bob);
        FakeSocket aliceSocket = connect(id, alice);
        clock.advance(Rules.RACE_CONNECT_WAIT.plus(Rules.RACE_COUNTDOWN));
        rooms.advance();

        // when
        rooms.receive(id, alice.id(), aliceSocket, new RaceMessage.Finish(900));

        // then
        assertThat(aliceSocket.messages.getLast(), equalTo(json("""
                {'type':'results','places':[
                {'slot':0,'nick':'Alice','kind':'human','place':1,'ticks':900,'left':false},
                {'slot':1,'nick':'Bob','kind':'human','place':null,'ticks':null,'left':true}]}""")));
        assertThat(aliceSocket.closeReason, equalTo(Room.RACE_OVER));
    }

    // A starting room nobody connects to must not hold its place under the
    // cap: it empties and goes with no socket event at all.
    @Test
    void shouldFreeRoomCap_whenCreate_givenStartingRoomsNobodyConnectedTo() {
        // given
        List<String> ids = fillWithStartedRooms();
        clock.advance(Rules.RACE_CONNECT_WAIT);
        RoomView emptied = rooms.find(ids.getFirst(), alice);
        clock.advance(Rules.EMPTY_ROOM_LIFETIME);
        RoomView stillKept = rooms.find(ids.getFirst(), alice);
        ApiException refused = assertThrows(ApiException.class, () -> rooms.create(alice));
        clock.advance(MILLISECOND);

        // when
        String newId = rooms.create(alice);

        // then
        assertThat(emptied, equalTo(emptyLobby(ids.getFirst())));
        assertThat(stillKept, equalTo(emptyLobby(ids.getFirst())));
        assertThat(refused.status(), equalTo(Response.Status.TOO_MANY_REQUESTS));
        assertThat(rooms.find(newId, alice).players().size(), equalTo(1));
        assertNotFound(ids.getFirst());
    }

    @Test
    void shouldFreeRoomCap_whenCreate_givenOneLateAccessAfterNobodyConnected() {
        // given
        List<String> ids = fillWithStartedRooms();
        clock.advance(Rules.RACE_CONNECT_WAIT.plus(Rules.EMPTY_ROOM_LIFETIME).plus(MILLISECOND));

        // when
        String newId = rooms.create(alice);

        // then
        assertThat(rooms.find(newId, alice).players().size(), equalTo(1));
        assertNotFound(ids.getFirst());
    }

    @Test
    void shouldRelayPositionToOthersOnly_whenReceive_givenRacing() {
        // given
        Race race = racingRoom();

        // when
        rooms.receive(race.id(), alice.id(), race.alice(), new RaceMessage.Position(2.5, 7, 360));

        // then
        assertThat(race.bob().messages, equalTo(List.of(
                json("{'type':'pos','slot':0,'nick':'Alice','x':2.5,'y':7.0,'tick':360}"))));
        assertThat(race.alice().messages, empty());
    }

    @Test
    void shouldAnswerNotRacing_whenReceive_givenPositionWhileStarting() {
        // given
        String id = startedRoom(alice, bob);
        FakeSocket aliceSocket = connect(id, alice);
        FakeSocket bobSocket = connect(id, bob);

        // when
        rooms.receive(id, alice.id(), aliceSocket, new RaceMessage.Position(1, 1, 1));

        // then
        assertThat(aliceSocket.messages.getLast(), equalTo(error(Room.NOT_RACING)));
        assertThat(types(bobSocket), equalTo(List.of("state", "countdown")));
    }

    @Test
    void shouldDropPositionsOverTen_whenReceive_givenElevenInOneSecond() {
        // given
        Race race = racingRoom();
        for (int i = 0; i < Rules.RACE_POSITIONS_PER_SECOND; i++) {
            rooms.receive(race.id(), alice.id(), race.alice(), new RaceMessage.Position(i, 0, i));
            clock.advance(Duration.ofMillis(99));
        }

        // when
        rooms.receive(race.id(), alice.id(), race.alice(), new RaceMessage.Position(99, 0, 99));
        clock.advance(Duration.ofMillis(10));
        rooms.receive(race.id(), alice.id(), race.alice(), new RaceMessage.Position(100, 0, 100));

        // then
        assertThat(race.bob().messages.size(), equalTo(Rules.RACE_POSITIONS_PER_SECOND + 1));
        assertThat(race.bob().messages.getLast().getInteger("tick"), equalTo(100));
        assertThat(race.alice().messages, empty());
    }

    @Test
    void shouldAnswerPong_whenReceive_givenPing() {
        // given
        String id = startedRoom(alice, bob);
        FakeSocket socket = connect(id, alice);

        // when
        rooms.receive(id, alice.id(), socket, new RaceMessage.Ping());

        // then
        assertThat(socket.messages.getLast(), equalTo(PONG));
    }

    @Test
    void shouldAnswerBadMessageAndKeepRace_whenReceive_givenBadMessage() {
        // given
        Race race = racingRoom();

        // when
        rooms.receive(race.id(), alice.id(), race.alice(), new RaceMessage.Bad());

        // then
        assertThat(race.alice().messages, equalTo(List.of(error(Room.BAD_MESSAGE))));
        assertThat(race.alice().closeReason, nullValue());
        assertThat(rooms.find(race.id(), alice).state(), equalTo(RACING));
    }

    @Test
    void shouldPlaceInOrderReceived_whenReceive_givenEveryoneFinished() {
        // given
        Race race = racingRoom();
        rooms.receive(race.id(), bob.id(), race.bob(), new RaceMessage.Finish(900));

        // when
        rooms.receive(race.id(), alice.id(), race.alice(), new RaceMessage.Finish(800));

        // then
        JsonObject results = json("""
                {'type':'results','places':[
                {'slot':1,'nick':'Bob','kind':'human','place':1,'ticks':900,'left':false},
                {'slot':0,'nick':'Alice','kind':'human','place':2,'ticks':800,'left':false}]}""");
        assertThat(race.alice().messages, equalTo(List.of(results)));
        assertThat(race.bob().messages, equalTo(List.of(results)));
        assertThat(race.alice().closeReason, equalTo(Room.RACE_OVER));
        assertThat(race.bob().closeReason, equalTo(Room.RACE_OVER));
    }

    @Test
    void shouldAnswerAlreadyFinished_whenReceive_givenSecondFinish() {
        // given
        Race race = racingRoom();
        rooms.receive(race.id(), alice.id(), race.alice(), new RaceMessage.Finish(800));

        // when
        rooms.receive(race.id(), alice.id(), race.alice(), new RaceMessage.Finish(700));

        // then
        assertThat(race.alice().messages, equalTo(List.of(error(Room.ALREADY_FINISHED))));
    }

    @Test
    void shouldAnswerNotRacing_whenReceive_givenFinishWhileStarting() {
        // given
        String id = startedRoom(alice, bob);
        FakeSocket socket = connect(id, alice);

        // when
        rooms.receive(id, alice.id(), socket, new RaceMessage.Finish(800));

        // then
        assertThat(socket.messages.getLast(), equalTo(error(Room.NOT_RACING)));
    }

    @Test
    void shouldKeepRacing_whenAdvance_givenJustUnderThirtySecondsAfterFirstFinish() {
        // given
        Race race = racingRoom();
        rooms.receive(race.id(), alice.id(), race.alice(), new RaceMessage.Finish(800));
        pingBoth(race, Rules.RACE_AFTER_FIRST_FINISH.minus(MILLISECOND));

        // when
        rooms.advance();

        // then
        assertThat(race.bob().messages, empty());
        assertThat(rooms.find(race.id(), alice).state(), equalTo(RACING));
    }

    @Test
    void shouldSendResults_whenAdvance_givenThirtySecondsAfterFirstFinish() {
        // given
        Race race = racingRoom();
        rooms.receive(race.id(), alice.id(), race.alice(), new RaceMessage.Finish(800));
        clock.advance(Rules.RACE_AFTER_FIRST_FINISH);

        // when
        rooms.advance();

        // then
        assertThat(race.bob().messages, equalTo(List.of(json("""
                {'type':'results','places':[
                {'slot':0,'nick':'Alice','kind':'human','place':1,'ticks':800,'left':false},
                {'slot':1,'nick':'Bob','kind':'human','place':null,'ticks':null,'left':false}]}"""))));
        assertThat(race.bob().closeReason, equalTo(Room.RACE_OVER));
        assertThat(rooms.find(race.id(), alice).state(), equalTo(LOBBY));
    }

    @Test
    void shouldKeepRacing_whenAdvance_givenJustUnderFourMinutesAfterGo() {
        // given
        Race race = racingRoom();
        pingBoth(race, Rules.RACE_CAP.minus(MILLISECOND));

        // when
        rooms.advance();

        // then
        assertThat(rooms.find(race.id(), alice).state(), equalTo(RACING));
    }

    @Test
    void shouldSendResults_whenAdvance_givenFourMinutesAfterGo() {
        // given
        Race race = racingRoom();
        pingBoth(race, Rules.RACE_CAP);

        // when
        rooms.advance();

        // then
        assertThat(types(race.alice()), equalTo(List.of("results")));
        assertThat(race.alice().closeReason, equalTo(Room.RACE_OVER));
        assertThat(rooms.find(race.id(), alice).state(), equalTo(LOBBY));
    }

    // Without the end counting as seen, a race longer than the lobby's 60 s
    // of silence would empty the room at the next sweep.
    @Test
    void shouldClearReadyAndCountMembersSeenAtEnd_whenFind_givenRaceLongerThanLobbySilence() {
        // given
        Race race = racingRoom();
        pingBoth(race, Rules.RACE_CAP);
        rooms.advance();
        clock.advance(Rules.LOBBY_MEMBER_SILENCE);

        // when
        RoomView room = rooms.find(race.id(), carol);

        // then
        assertThat(room, equalTo(new RoomView(race.id(), LOBBY, new RoomView.Host("Alice"),
                List.of(new RoomView.RoomPlayer("Alice", Rules.KIND_HUMAN, false, true, false),
                        new RoomView.RoomPlayer("Bob", Rules.KIND_HUMAN, false, false, false)),
                Rules.LEVEL_MIN, Rules.SHAPE_LANDSCAPE, Rules.ROOM_SIZE)));
    }

    @Test
    void shouldRemoveSilentMembers_whenFind_givenLobbySilenceSinceRaceEnd() {
        // given
        Race race = racingRoom();
        pingBoth(race, Rules.RACE_CAP);
        rooms.advance();
        clock.advance(Rules.LOBBY_MEMBER_SILENCE.plus(MILLISECOND));

        // when
        RoomView room = rooms.find(race.id(), carol);

        // then
        assertThat(room, equalTo(emptyLobby(race.id())));
    }

    @Test
    void shouldResumeWithOthersLastPositions_whenConnect_givenReconnectWithinThirtySeconds() {
        // given
        Race race = racingRoom();
        rooms.receive(race.id(), alice.id(), race.alice(), new RaceMessage.Position(4, 5, 600));
        rooms.receive(race.id(), bob.id(), race.bob(), new RaceMessage.Position(6, 7, 610));
        rooms.disconnect(race.id(), bob.id(), race.bob());
        clock.advance(Rules.RACE_RECONNECT_WAIT);
        rooms.advance();

        // when
        FakeSocket bobSocket = connect(race.id(), bob);

        // then
        assertThat(bobSocket.messages, equalTo(List.of(json("""
                {'type':'state','state':'racing','level':1,'shape':'landscape','players':[
                {'slot':0,'nick':'Alice','kind':'human','you':false,'connected':true,'finished':false},
                {'slot':1,'nick':'Bob','kind':'human','you':true,'connected':true,'finished':false}],
                'positions':[{'slot':0,'x':4.0,'y':5.0,'tick':600}]}"""))));
    }

    @Test
    void shouldCountMemberAsLeft_whenAdvance_givenSocketGoneMoreThanThirtySeconds() {
        // given
        Race race = racingRoom();
        rooms.disconnect(race.id(), bob.id(), race.bob());
        clock.advance(Rules.RACE_RECONNECT_WAIT.plus(MILLISECOND));
        rooms.receive(race.id(), alice.id(), race.alice(), new RaceMessage.Ping());

        // when
        rooms.advance();

        // then
        assertThat(rooms.raceRefusal(race.id(), bob.id()), equalTo(409));
        assertThat(rooms.find(race.id(), alice).players().size(), equalTo(1));
        assertThat(rooms.find(race.id(), alice).state(), equalTo(RACING));
    }

    @Test
    void shouldSendResults_whenReceive_givenLastOtherMemberLeftAndFinish() {
        // given
        Race race = racingRoom();
        rooms.disconnect(race.id(), bob.id(), race.bob());
        clock.advance(Rules.RACE_RECONNECT_WAIT.plus(MILLISECOND));
        rooms.advance();

        // when
        rooms.receive(race.id(), alice.id(), race.alice(), new RaceMessage.Finish(1000));

        // then
        assertThat(race.alice().messages, equalTo(List.of(json("""
                {'type':'results','places':[
                {'slot':0,'nick':'Alice','kind':'human','place':1,'ticks':1000,'left':false},
                {'slot':1,'nick':'Bob','kind':'human','place':null,'ticks':null,'left':true}]}"""))));
        assertThat(rooms.find(race.id(), alice).state(), equalTo(LOBBY));
    }

    @Test
    void shouldKeepSocketOpen_whenAdvance_givenNoMessageForExactlySixtySeconds() {
        // given
        Race race = racingRoom();
        clock.advance(Rules.RACE_SOCKET_IDLE.minus(Rules.RACE_COUNTDOWN));

        // when
        rooms.advance();

        // then
        assertThat(race.alice().closeReason, nullValue());
    }

    @Test
    void shouldCloseIdleSocket_whenAdvance_givenNoMessageForMoreThanSixtySeconds() {
        // given
        Race race = racingRoom();
        clock.advance(Rules.RACE_SOCKET_IDLE.minus(Rules.RACE_COUNTDOWN).minusSeconds(1));
        rooms.receive(race.id(), bob.id(), race.bob(), new RaceMessage.Ping());
        clock.advance(Duration.ofSeconds(1).plus(MILLISECOND));

        // when
        rooms.advance();

        // then
        assertThat(race.alice().closeReason, equalTo(Room.IDLE));
        assertThat(race.bob().closeReason, nullValue());
    }

    @Test
    void shouldCountIdleMemberAsLeft_whenAdvance_givenThirtySecondsAfterIdleClose() {
        // given
        Race race = racingRoom();
        clock.advance(Rules.RACE_SOCKET_IDLE.minus(Rules.RACE_COUNTDOWN));
        rooms.receive(race.id(), bob.id(), race.bob(), new RaceMessage.Ping());
        clock.advance(Rules.RACE_RECONNECT_WAIT.plus(MILLISECOND.multipliedBy(2)));
        rooms.receive(race.id(), bob.id(), race.bob(), new RaceMessage.Ping());

        // when
        rooms.advance();

        // then
        assertThat(rooms.raceRefusal(race.id(), alice.id()), equalTo(409));
        assertThat(rooms.find(race.id(), bob).players().size(), equalTo(1));
    }

    @Test
    void shouldStartCountdown_whenLeave_givenOnlyUnconnectedMemberLeaves() {
        // given
        String id = startedRoom(alice, bob, carol);
        FakeSocket aliceSocket = connect(id, alice);
        FakeSocket bobSocket = connect(id, bob);

        // when
        rooms.leave(id, carol);

        // then
        assertThat(aliceSocket.messages.getLast(), equalTo(COUNTDOWN));
        assertThat(bobSocket.messages.getLast(), equalTo(COUNTDOWN));
    }

    @Test
    void shouldReturnToLobby_whenLeave_givenEveryMemberLeavesStartingRoom() {
        // given
        String id = startedRoom(alice, bob);
        rooms.leave(id, alice);

        // when
        rooms.leave(id, bob);

        // then
        assertThat(rooms.find(id, carol), equalTo(emptyLobby(id)));
        assertThat(rooms.raceRefusal(id, bob.id()), equalTo(409));
    }

    @Test
    void shouldCountAsLeftAndCloseSocket_whenLeave_givenRacing() {
        // given
        String id = startedRoom(alice, bob, carol);
        FakeSocket aliceSocket = connect(id, alice);
        connect(id, bob);
        connect(id, carol);
        clock.advance(Rules.RACE_COUNTDOWN);
        rooms.advance();

        // when
        rooms.leave(id, alice);

        // then
        assertThat(aliceSocket.closeReason, equalTo(Room.RACE_OVER));
        assertThat(rooms.find(id, bob).host(), equalTo(new RoomView.Host("Bob")));
        assertThat(rooms.find(id, bob).state(), equalTo(RACING));
    }

    @Test
    void shouldSendResults_whenLeave_givenLastUnfinishedMemberLeaves() {
        // given
        Race race = racingRoom();
        rooms.receive(race.id(), alice.id(), race.alice(), new RaceMessage.Finish(800));

        // when
        rooms.leave(race.id(), bob);

        // then
        assertThat(race.alice().messages, equalTo(List.of(json("""
                {'type':'results','places':[
                {'slot':0,'nick':'Alice','kind':'human','place':1,'ticks':800,'left':false},
                {'slot':1,'nick':'Bob','kind':'human','place':null,'ticks':null,'left':true}]}"""))));
        assertThat(race.bob().closeReason, equalTo(Room.RACE_OVER));
        assertThat(rooms.find(race.id(), alice).state(), equalTo(LOBBY));
    }

    @Test
    void shouldCountAsLeft_whenForget_givenRacing() {
        // given
        Race race = racingRoom();

        // when
        rooms.forget(bob.id());

        // then
        assertThat(race.bob().closeReason, equalTo(Room.RACE_OVER));
        assertThat(rooms.find(race.id(), alice).players().size(), equalTo(1));
    }

    @Test
    void shouldCountAsLeft_whenJoin_givenMemberJoinsOtherRoomDuringRace() {
        // given
        Race race = racingRoom();
        String otherId = rooms.create(carol);

        // when
        rooms.join(otherId, bob);

        // then
        assertThat(race.bob().closeReason, equalTo(Room.RACE_OVER));
        assertThat(rooms.raceRefusal(race.id(), bob.id()), equalTo(409));
    }

    @Test
    void shouldCloseRaceSockets_whenCreate_givenHostOfRacingRoom() {
        // given
        Race race = racingRoom();

        // when
        rooms.create(alice);

        // then
        assertThat(race.alice().closeReason, equalTo(Room.RACE_OVER));
        assertThat(race.bob().closeReason, equalTo(Room.RACE_OVER));
        assertThat(race.bob().messages, empty());
        assertNotFound(race.id());
    }

    // One late call walks the countdown, go and the end in order, each at
    // the time it fell due: the race ended 30 s after Alice's finish, before
    // either socket was idle, and the members' lobby silence counts from there.
    @Test
    void shouldEndRaceAtItsOwnTime_whenAdvance_givenOneLateCall() {
        // given
        String id = startedRoom(alice, bob);
        FakeSocket aliceSocket = connect(id, alice);
        FakeSocket bobSocket = connect(id, bob);
        clock.advance(Rules.RACE_COUNTDOWN.plusSeconds(10));
        rooms.receive(id, alice.id(), aliceSocket, new RaceMessage.Finish(1200));
        clock.advance(Duration.ofMinutes(10));

        // when
        rooms.advance();

        // then
        assertThat(types(bobSocket), equalTo(List.of("state", "countdown", "go", "results")));
        assertThat(bobSocket.closeReason, equalTo(Room.RACE_OVER));
        assertThat(bobSocket.messages.getLast(), equalTo(json("""
                {'type':'results','places':[
                {'slot':0,'nick':'Alice','kind':'human','place':1,'ticks':1200,'left':false},
                {'slot':1,'nick':'Bob','kind':'human','place':null,'ticks':null,'left':false}]}""")));
        assertThat(rooms.find(id, carol), equalTo(emptyLobby(id)));
    }

    @Test
    void shouldSendCountdownAndGo_whenAdvance_givenOneLateCallAfterConnectWait() {
        // given
        String id = startedRoom(alice, bob);
        FakeSocket aliceSocket = connect(id, alice);
        clock.advance(Rules.RACE_CONNECT_WAIT.plus(Rules.RACE_COUNTDOWN));

        // when
        rooms.advance();

        // then
        assertThat(aliceSocket.messages.getLast(), equalTo(json("{'type':'go'}")
                .put("at", START.plus(Rules.RACE_CONNECT_WAIT).plus(Rules.RACE_COUNTDOWN).toEpochMilli())));
        assertThat(types(aliceSocket), equalTo(List.of("state", "countdown", "go")));
    }

    private String startedRoom(LobbyPlayer... players) {
        String id = rooms.create(players[0]);
        for (int i = 1; i < players.length; i++) {
            rooms.join(id, players[i]);
        }
        for (LobbyPlayer player : players) {
            rooms.setReady(id, player, true);
        }
        rooms.start(id, players[0]);
        return id;
    }

    // Both members connect at once, so go comes 3 s later; what was sent
    // before go is cleared.
    private Race racingRoom() {
        String id = startedRoom(alice, bob);
        FakeSocket aliceSocket = connect(id, alice);
        FakeSocket bobSocket = connect(id, bob);
        clock.advance(Rules.RACE_COUNTDOWN);
        rooms.advance();
        aliceSocket.messages.clear();
        bobSocket.messages.clear();
        return new Race(id, aliceSocket, bobSocket);
    }

    private List<String> fillWithStartedRooms() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < Rules.ROOMS_MAX; i++) {
            ids.add(startedRoom(player("Host" + i), player("Guest" + i)));
        }
        return ids;
    }

    // Pings on the way keep both sockets clear of the idle close; the last
    // stretch has none, so nothing reaches the room at the end time itself.
    private void pingBoth(Race race, Duration duration) {
        Duration step = Rules.RACE_SOCKET_IDLE.dividedBy(2);
        Duration rest = duration;
        while (step.compareTo(rest) < 0) {
            clock.advance(step);
            rest = rest.minus(step);
            rooms.receive(race.id(), alice.id(), race.alice(), new RaceMessage.Ping());
            rooms.receive(race.id(), bob.id(), race.bob(), new RaceMessage.Ping());
        }
        clock.advance(rest);
        race.alice().messages.clear();
        race.bob().messages.clear();
    }

    private FakeSocket connect(String id, LobbyPlayer player) {
        FakeSocket socket = new FakeSocket();
        rooms.connect(id, player.id(), socket);
        return socket;
    }

    private void assertNotFound(String id) {
        ApiException exception = assertThrows(ApiException.class, () -> rooms.find(id, alice));
        assertThat(exception.status(), equalTo(Response.Status.NOT_FOUND));
    }

    private static RoomView emptyLobby(String id) {
        return new RoomView(id, LOBBY, null, List.of(), Rules.LEVEL_MIN, Rules.SHAPE_LANDSCAPE, Rules.ROOM_SIZE);
    }

    private static List<String> types(FakeSocket socket) {
        return socket.messages.stream().map(message -> message.getString("type")).toList();
    }

    private static JsonObject error(String code) {
        return json("{'type':'error'}").put("error", code);
    }

    // Single quotes keep the expected messages readable.
    private static JsonObject json(String text) {
        return new JsonObject(text.replace('\'', '"'));
    }

    private static LobbyPlayer player(String nick) {
        return new LobbyPlayer(UUID.randomUUID(), nick, Rules.KIND_HUMAN, true);
    }

    private record Race(String id, FakeSocket alice, FakeSocket bob) {
    }
}
