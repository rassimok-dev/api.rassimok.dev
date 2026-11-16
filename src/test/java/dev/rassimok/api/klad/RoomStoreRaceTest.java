package dev.rassimok.api.klad;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

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
                List.of(new RoomView.RoomPlayer("Alice", Rules.KIND_HUMAN, true, true, true, 1),
                        new RoomView.RoomPlayer("Carol", Rules.KIND_HUMAN, true, false, false, 3)),
                Rules.LEVEL_MIN, Rules.SHAPE_LANDSCAPE, Rules.ROOM_SIZE, true, List.of())));
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
    void shouldListSlotsZeroToNineInJoinOrder_whenConnect_givenRoomOfTen() {
        // given
        List<LobbyPlayer> players = roomOfTen();
        String id = startedRoom(players.toArray(LobbyPlayer[]::new));
        players.subList(0, players.size() - 1).forEach(player -> connect(id, player));

        // when
        FakeSocket lastSocket = connect(id, players.getLast());

        // then
        JsonArray expectedPlayers = new JsonArray();
        for (int slot = 0; slot < 10; slot++) {
            expectedPlayers.add(new JsonObject().put("slot", slot).put("nick", players.get(slot).nick())
                    .put("kind", Rules.KIND_HUMAN).put("you", 9 == slot).put("connected", true).put("finished", false));
        }
        assertThat(lastSocket.messages, equalTo(List.of(
                json("{'type':'state','state':'starting','level':1,'shape':'landscape','positions':[]}")
                        .put("players", expectedPlayers),
                COUNTDOWN)));
    }

    // All ten send in the same instant, as clients that share one go time do.
    @Test
    void shouldRelayEachPositionToTheNineOthersOnly_whenReceive_givenRaceOfTen() {
        // given
        RaceOfTen race = racingRoomOfTen();

        // when
        for (int slot = 0; slot < race.players().size(); slot++) {
            rooms.receive(race.id(), race.players().get(slot).id(), race.sockets().get(slot),
                    new RaceMessage.Position(slot, 1, slot));
        }

        // then
        for (int receiver = 0; receiver < race.players().size(); receiver++) {
            int receiverSlot = receiver;
            List<JsonObject> expected = IntStream.range(0, race.players().size())
                    .filter(sender -> receiverSlot != sender)
                    .mapToObj(sender -> json("{'type':'pos','y':1.0}").put("slot", sender)
                            .put("nick", race.players().get(sender).nick()).put("x", (double) sender).put("tick", sender))
                    .toList();
            assertThat(race.sockets().get(receiver).messages, equalTo(expected));
        }
    }

    @Test
    void shouldPlaceAllTenInOrderReceived_whenReceive_givenLastOfTenFinishes() {
        // given
        RaceOfTen race = racingRoomOfTen();
        for (int slot = 9; slot > 0; slot--) {
            rooms.receive(race.id(), race.players().get(slot).id(), race.sockets().get(slot),
                    new RaceMessage.Finish(1000 + slot));
        }

        // when
        rooms.receive(race.id(), race.players().getFirst().id(), race.sockets().getFirst(), new RaceMessage.Finish(1000));

        // then
        JsonArray places = new JsonArray();
        for (int place = 1; place <= 10; place++) {
            int slot = 10 - place;
            places.add(new JsonObject().put("slot", slot).put("nick", race.players().get(slot).nick())
                    .put("kind", Rules.KIND_HUMAN).put("place", place).put("ticks", 1000 + slot).put("left", false));
        }
        JsonObject results = json("{'type':'results'}").put("places", places);
        for (FakeSocket socket : race.sockets()) {
            assertThat(socket.messages, equalTo(List.of(results)));
            assertThat(socket.closeReason, equalTo(Room.RACE_OVER));
        }
        assertThat(rooms.find(race.id(), alice).state(), equalTo(LOBBY));
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
        RoomView room = rooms.find(race.id(), alice);

        // then
        assertThat(room, equalTo(new RoomView(race.id(), LOBBY, new RoomView.Host("Alice"),
                List.of(new RoomView.RoomPlayer("Alice", Rules.KIND_HUMAN, false, true, true, 1),
                        new RoomView.RoomPlayer("Bob", Rules.KIND_HUMAN, false, false, false, 2)),
                Rules.LEVEL_MIN, Rules.SHAPE_LANDSCAPE, Rules.ROOM_SIZE, true, List.of())));
    }

    @Test
    void shouldRemoveSilentMembers_whenFind_givenLobbySilenceSinceRaceEnd() {
        // given
        Race race = racingRoom();
        pingBoth(race, Rules.RACE_CAP);
        rooms.advance();
        clock.advance(Rules.LOBBY_MEMBER_SILENCE.plus(MILLISECOND));

        // when
        RoomView seenByAlice = rooms.find(race.id(), alice);
        RoomView seenByBob = rooms.find(race.id(), bob);

        // then
        assertThat(seenByAlice, equalTo(emptyLobby(race.id())));
        assertThat(seenByBob, equalTo(emptyLobby(race.id())));
    }

    @Test
    void shouldKeepMessageAndSendChatToOtherSocketOnly_whenSend_givenRoomRacing() {
        // given
        Race race = racingRoom();

        // when
        RoomView.Message sent = rooms.send(race.id(), bob, "go go");

        // then
        assertThat(sent, equalTo(new RoomView.Message(1, 2, "Bob", Rules.KIND_HUMAN, true, true, "go go")));
        assertThat(rooms.find(race.id(), alice).messages(),
                equalTo(List.of(new RoomView.Message(1, 2, "Bob", Rules.KIND_HUMAN, true, false, "go go"))));
        assertThat(race.alice().messages, equalTo(List.of(chat(1, 2, 1, "Bob", "go go"))));
        assertThat(race.bob().messages, empty());
    }

    @Test
    void shouldSendChatToConnectedOthersOnly_whenSend_givenRoomStartingAndOneMemberNotConnected() {
        // given
        String id = startedRoom(alice, bob, carol);
        FakeSocket aliceSocket = connect(id, alice);
        FakeSocket bobSocket = connect(id, bob);
        aliceSocket.messages.clear();
        bobSocket.messages.clear();

        // when
        rooms.send(id, bob, "wait for me");

        // then
        assertThat(aliceSocket.messages, equalTo(List.of(chat(1, 2, 1, "Bob", "wait for me"))));
        assertThat(bobSocket.messages, empty());
    }

    // The room poll shows it later; the socket does not replay it.
    @Test
    void shouldSendNoEarlierChat_whenConnect_givenChatSentBeforeMemberConnected() {
        // given
        String id = startedRoom(alice, bob, carol);
        connect(id, alice);
        connect(id, bob);
        rooms.send(id, bob, "wait for me");

        // when
        FakeSocket carolSocket = connect(id, carol);

        // then
        assertThat(types(carolSocket), equalTo(List.of("state", "countdown")));
        assertThat(rooms.find(id, carol).messages(),
                equalTo(List.of(new RoomView.Message(1, 2, "Bob", Rules.KIND_HUMAN, true, false, "wait for me"))));
    }

    @Test
    void shouldSendChatBetweenCountdownAndGo_whenSend_givenCountdownRunning() {
        // given
        String id = startedRoom(alice, bob);
        FakeSocket aliceSocket = connect(id, alice);
        FakeSocket bobSocket = connect(id, bob);
        clock.advance(Duration.ofSeconds(1));

        // when
        rooms.send(id, bob, "ready");
        clock.advance(Duration.ofSeconds(2));
        rooms.advance();

        // then
        assertThat(types(aliceSocket), equalTo(List.of("state", "countdown", "chat", "go")));
        assertThat(aliceSocket.messages.get(2), equalTo(chat(1, 2, 1, "Bob", "ready")));
        assertThat(types(bobSocket), equalTo(List.of("state", "countdown", "go")));
    }

    @Test
    void shouldSendChatToFinishedMember_whenSend_givenOtherMemberFinished() {
        // given
        Race race = racingRoom();
        rooms.receive(race.id(), alice.id(), race.alice(), new RaceMessage.Finish(800));

        // when
        rooms.send(race.id(), bob, "well run");

        // then
        assertThat(race.alice().messages, equalTo(List.of(chat(1, 2, 1, "Bob", "well run"))));
        assertThat(race.bob().messages, empty());
    }

    @Test
    void shouldSendChatOfFinishedMember_whenSend_givenSenderFinished() {
        // given
        Race race = racingRoom();
        rooms.receive(race.id(), alice.id(), race.alice(), new RaceMessage.Finish(800));

        // when
        rooms.send(race.id(), alice, "the exit is left");

        // then
        assertThat(race.bob().messages, equalTo(List.of(chat(1, 1, 0, "Alice", "the exit is left"))));
        assertThat(race.alice().messages, empty());
    }

    @Test
    void shouldSendNoChatToMemberWhoLeft_whenSend_givenRacing() {
        // given
        String id = startedRoom(alice, bob, carol);
        FakeSocket aliceSocket = connect(id, alice);
        FakeSocket bobSocket = connect(id, bob);
        FakeSocket carolSocket = connect(id, carol);
        clock.advance(Rules.RACE_COUNTDOWN);
        rooms.advance();
        rooms.leave(id, carol);
        aliceSocket.messages.clear();
        bobSocket.messages.clear();
        carolSocket.messages.clear();

        // when
        rooms.send(id, bob, "bye Carol");

        // then
        assertThat(aliceSocket.messages, equalTo(List.of(chat(1, 2, 1, "Bob", "bye Carol"))));
        assertThat(bobSocket.messages, empty());
        assertThat(carolSocket.messages, empty());
        assertThat(carolSocket.closeReason, equalTo(Room.RACE_OVER));
    }

    // Gone, but not yet counted as left: the room poll shows it after the race.
    @Test
    void shouldSendNoChatToGoneSocket_whenSend_givenMemberDisconnected() {
        // given
        Race race = racingRoom();
        rooms.disconnect(race.id(), bob.id(), race.bob());

        // when
        rooms.send(race.id(), alice, "where are you");
        FakeSocket bobSocket = connect(race.id(), bob);

        // then
        assertThat(race.bob().messages, empty());
        assertThat(types(bobSocket), equalTo(List.of("state")));
        assertThat(rooms.find(race.id(), bob).messages(),
                equalTo(List.of(new RoomView.Message(1, 1, "Alice", Rules.KIND_HUMAN, true, false, "where are you"))));
    }

    @Test
    void shouldSendChatToNewSocketOnly_whenSend_givenSocketReplaced() {
        // given
        Race race = racingRoom();
        FakeSocket newBobSocket = connect(race.id(), bob);
        newBobSocket.messages.clear();

        // when
        rooms.send(race.id(), alice, "hi");

        // then
        assertThat(race.bob().messages, empty());
        assertThat(newBobSocket.messages, equalTo(List.of(chat(1, 1, 0, "Alice", "hi"))));
    }

    @Test
    void shouldSendNoChat_whenSend_givenSixthMessageWithinWindow() {
        // given
        Race race = racingRoom();
        for (int number = 1; number <= Rules.CHAT_MESSAGES_PER_WINDOW; number++) {
            rooms.send(race.id(), bob, "message " + number);
        }

        // when
        ApiException refused = assertThrows(ApiException.class, () -> rooms.send(race.id(), bob, "one too many"));

        // then
        assertThat(refused.status(), equalTo(Response.Status.TOO_MANY_REQUESTS));
        assertThat(race.alice().messages, equalTo(IntStream.rangeClosed(1, Rules.CHAT_MESSAGES_PER_WINDOW)
                .mapToObj(number -> chat(number, 2, 1, "Bob", "message " + number))
                .toList()));
        assertThat(race.bob().messages, empty());
    }

    @Test
    void shouldSendNoChat_whenSend_givenPlayerNotInRacingRoom() {
        // given
        Race race = racingRoom();

        // when
        ApiException refused = assertThrows(ApiException.class, () -> rooms.send(race.id(), carol, "let me in"));

        // then
        assertThat(refused.status(), equalTo(Response.Status.NOT_FOUND));
        assertThat(race.alice().messages, empty());
        assertThat(race.bob().messages, empty());
    }

    // Everything one socket gets keeps the order the room made it in, and a
    // chat message after the results reaches no socket.
    @Test
    void shouldKeepOrderWithPositionsAndResults_whenSend_givenRaceRunsToItsEnd() {
        // given
        Race race = racingRoom();

        // when
        rooms.receive(race.id(), alice.id(), race.alice(), new RaceMessage.Position(1, 1, 10));
        rooms.send(race.id(), alice, "catch me");
        rooms.receive(race.id(), alice.id(), race.alice(), new RaceMessage.Position(2, 1, 20));
        rooms.send(race.id(), bob, "soon");
        rooms.receive(race.id(), alice.id(), race.alice(), new RaceMessage.Finish(900));
        rooms.receive(race.id(), bob.id(), race.bob(), new RaceMessage.Finish(950));
        rooms.send(race.id(), alice, "good race");

        // then
        assertThat(types(race.bob()), equalTo(List.of("pos", "chat", "pos", "results")));
        assertThat(race.bob().messages.get(1), equalTo(chat(1, 1, 0, "Alice", "catch me")));
        assertThat(types(race.alice()), equalTo(List.of("chat", "results")));
        assertThat(race.alice().messages.getFirst(), equalTo(chat(2, 2, 1, "Bob", "soon")));
        assertThat(rooms.find(race.id(), bob).messages(), equalTo(List.of(
                new RoomView.Message(1, 1, "Alice", Rules.KIND_HUMAN, true, false, "catch me"),
                new RoomView.Message(2, 2, "Bob", Rules.KIND_HUMAN, true, true, "soon"),
                new RoomView.Message(3, 1, "Alice", Rules.KIND_HUMAN, true, false, "good race"))));
    }

    // The send applies the race's end first, so the message is kept but the
    // sockets get only the results.
    @Test
    void shouldKeepMessageAndSendNoChat_whenSend_givenRaceEndFellDue() {
        // given
        Race race = racingRoom();
        pingBoth(race, Rules.RACE_CAP);

        // when
        rooms.send(race.id(), alice, "too late");

        // then
        assertThat(types(race.alice()), equalTo(List.of("results")));
        assertThat(types(race.bob()), equalTo(List.of("results")));
        assertThat(rooms.find(race.id(), bob).messages(),
                equalTo(List.of(new RoomView.Message(1, 1, "Alice", Rules.KIND_HUMAN, true, false, "too late"))));
    }

    @Test
    void shouldSendEachChatToTheNineOthersOnly_whenSend_givenRaceOfTen() {
        // given
        RaceOfTen race = racingRoomOfTen();

        // when
        for (int slot = 0; slot < race.players().size(); slot++) {
            rooms.send(race.id(), race.players().get(slot), "from " + slot);
        }

        // then
        for (int receiver = 0; receiver < race.players().size(); receiver++) {
            int receiverSlot = receiver;
            List<JsonObject> expected = IntStream.range(0, race.players().size())
                    .filter(sender -> receiverSlot != sender)
                    .mapToObj(sender -> chat(sender + 1, sender + 1, sender, race.players().get(sender).nick(),
                            "from " + sender))
                    .toList();
            assertThat(race.sockets().get(receiver).messages, equalTo(expected));
        }
    }

    // The marks are the sender's at the time of sending, as the room poll
    // shows them; the text goes out as JSON whatever its quotes.
    @Test
    void shouldCarrySendersMarksAsSent_whenSend_givenAgentAccountRenamedDuringRace() {
        // given
        LobbyPlayer dana = new LobbyPlayer(UUID.randomUUID(), "Dana", Rules.KIND_AGENT, false);
        LobbyPlayer renamedDana = new LobbyPlayer(dana.id(), "Danielle", Rules.KIND_AGENT, false);
        String id = startedRoom(alice, dana);
        FakeSocket aliceSocket = connect(id, alice);
        connect(id, dana);
        clock.advance(Rules.RACE_COUNTDOWN);
        rooms.advance();
        aliceSocket.messages.clear();
        String text = "she said \"\u041F\u0440\u0438\u0432\u0435\u0442\" \\ ok";

        // when
        rooms.send(id, renamedDana, text);

        // then
        assertThat(aliceSocket.messages, equalTo(List.of(json("{'type':'chat','n':1,'member':2,'slot':1}")
                .put("nick", "Danielle").put("kind", Rules.KIND_AGENT).put("guest", false).put("text", text))));
    }

    @Test
    void shouldShowNoMembers_whenFind_givenPlayerNotInRacingRoom() {
        // given
        Race race = racingRoom();

        // when
        RoomView room = rooms.find(race.id(), carol);

        // then
        assertThat(room, equalTo(new RoomView(race.id(), RACING, null, List.of(), Rules.LEVEL_MIN,
                Rules.SHAPE_LANDSCAPE, Rules.ROOM_SIZE, true, List.of())));
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
        assertThat(rooms.find(id, alice), equalTo(emptyLobby(id)));
        assertThat(rooms.find(id, bob), equalTo(emptyLobby(id)));
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
        assertThat(rooms.find(id, alice), equalTo(emptyLobby(id)));
        assertThat(rooms.find(id, bob), equalTo(emptyLobby(id)));
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

    // Alice, Bob and Carol join first, so they hold slots 0 to 2.
    private List<LobbyPlayer> roomOfTen() {
        List<LobbyPlayer> players = new ArrayList<>(List.of(alice, bob, carol));
        for (int slot = 3; slot < 10; slot++) {
            players.add(player("Racer" + slot));
        }
        return players;
    }

    // Every member connects, so go comes 3 s later; what was sent before go
    // is cleared.
    private RaceOfTen racingRoomOfTen() {
        List<LobbyPlayer> players = roomOfTen();
        String id = startedRoom(players.toArray(LobbyPlayer[]::new));
        List<FakeSocket> sockets = players.stream().map(player -> connect(id, player)).toList();
        clock.advance(Rules.RACE_COUNTDOWN);
        rooms.advance();
        sockets.forEach(socket -> socket.messages.clear());
        return new RaceOfTen(id, players, sockets);
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
        return new RoomView(id, LOBBY, null, List.of(), Rules.LEVEL_MIN, Rules.SHAPE_LANDSCAPE, Rules.ROOM_SIZE, true,
                List.of());
    }

    private static List<String> types(FakeSocket socket) {
        return socket.messages.stream().map(message -> message.getString("type")).toList();
    }

    private static JsonObject error(String code) {
        return json("{'type':'error'}").put("error", code);
    }

    // As a human guest sends it.
    private static JsonObject chat(int number, int member, int slot, String nick, String text) {
        return json("{'type':'chat','kind':'human','guest':true}").put("n", number).put("member", member)
                .put("slot", slot).put("nick", nick).put("text", text);
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

    // The players and their sockets in slot order.
    private record RaceOfTen(String id, List<LobbyPlayer> players, List<FakeSocket> sockets) {
    }
}
