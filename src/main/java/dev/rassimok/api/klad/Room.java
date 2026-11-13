package dev.rassimok.api.klad;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * One room, its lobby and its race (contract sections 7 and 8). Not
 * thread-safe: RoomStore holds its lock around every use. Every race rule
 * reads the time it is given, so a late call applies each rule that fell
 * due in between, in order and dated when it fell due.
 */
final class Room {

    enum State {
        LOBBY, STARTING, RACING
    }

    static final String RACE_OVER = "race_over";
    static final String REPLACED = "replaced";
    static final String IDLE = "idle";
    static final String BAD_MESSAGE = "bad_message";
    static final String NOT_RACING = "not_racing";
    static final String ALREADY_FINISHED = "already_finished";

    private static final String TYPE_STATE = "state";
    private static final String TYPE_COUNTDOWN = "countdown";
    private static final String TYPE_GO = "go";
    private static final String TYPE_RESULTS = "results";
    private static final String TYPE_PONG = "pong";
    private static final String TYPE_ERROR = "error";
    private static final String FIELD_STATE = "state";
    private static final String FIELD_PLAYERS = "players";
    private static final String FIELD_POSITIONS = "positions";
    private static final String FIELD_SLOT = "slot";
    private static final String FIELD_NICK = "nick";
    private static final String FIELD_YOU = "you";
    private static final String FIELD_CONNECTED = "connected";
    private static final String FIELD_FINISHED = "finished";
    private static final String FIELD_SECONDS = "seconds";
    private static final String FIELD_AT = "at";
    private static final String FIELD_PLACES = "places";
    private static final String FIELD_PLACE = "place";
    private static final String FIELD_LEFT = "left";
    private static final String FIELD_ERROR = "error";

    static final class Member {

        final UUID playerId;
        final String kind;
        String nick;
        boolean isReady;
        Instant lastSeenAt;

        private Member(LobbyPlayer player, Instant now) {
            playerId = player.id();
            kind = player.kind();
            nick = player.nick();
            lastSeenAt = now;
        }
    }

    // A member as the race knows them, kept after they leave so the results
    // still list them.
    private static final class Racer {

        final UUID playerId;
        final int slot;
        final String nick;
        final String kind;
        PlayerSocket socket;
        Instant lastMessageAt;
        // Set while a socket that was connected is gone.
        Instant goneSince;
        Instant leftAt;
        Long finishTicks;
        Integer place;
        RaceMessage.Position position;
        // The positions accepted in the last second, oldest first.
        final Deque<Instant> recentPositions = new ArrayDeque<>();

        private Racer(Member member, int slot) {
            playerId = member.playerId;
            this.slot = slot;
            nick = member.nick;
            kind = member.kind;
        }

        boolean isInRace() {
            return null == leftAt;
        }

        boolean isConnected() {
            return null != socket;
        }

        boolean hasFinished() {
            return null != finishTicks;
        }
    }

    final String id;
    State state = State.LOBBY;
    int level = Rules.LEVEL_MIN;
    String shape = Rules.SHAPE_LANDSCAPE;
    // In join order; the first one is the host.
    private final List<Member> members = new ArrayList<>();
    // Kept when the room empties, so its last host's next room still closes it.
    private UUID hostId;
    private Instant emptySince;
    // In slot order, from the start to the results; empty in the lobby.
    private final List<Racer> racers = new ArrayList<>();
    private Instant startingAt;
    private Instant countdownAt;
    private Instant goAt;
    private Instant firstFinishAt;
    // The latest finish or leave, which dates the results when everyone has
    // finished or left.
    private Instant lastExitAt;

    Room(String id, Instant now) {
        this.id = id;
        emptySince = now;
    }

    Member member(UUID playerId) {
        return members.stream().filter(member -> playerId.equals(member.playerId)).findFirst().orElse(null);
    }

    boolean isHost(UUID playerId) {
        return !members.isEmpty() && playerId.equals(hostId);
    }

    boolean isHostOrLastHost(UUID playerId) {
        return playerId.equals(hostId);
    }

    boolean isFull() {
        return Rules.ROOM_SIZE <= members.size();
    }

    boolean canStart() {
        return Rules.ROOM_PLAYERS_TO_START <= members.size() && members.stream().allMatch(member -> member.isReady);
    }

    boolean isExpired(Instant now) {
        return members.isEmpty() && emptySince.plus(Rules.EMPTY_ROOM_LIFETIME).isBefore(now);
    }

    void add(LobbyPlayer player, Instant now) {
        members.add(new Member(player, now));
        hostId = members.getFirst().playerId;
        emptySince = null;
    }

    // In a race the member counts as left at once, and their socket closes.
    void leave(Member member, Instant now) {
        remove(member, now);
        advance(now);
    }

    void markSeen(LobbyPlayer player, Instant now) {
        Member member = member(player.id());
        if (null != member) {
            member.nick = player.nick();
            member.lastSeenAt = now;
        }
    }

    // A silent member counts as gone from the moment it fell silent, and the
    // last one to go dates the room's emptiness, so an abandoned room's ten
    // minutes do not wait for the next access.
    void removeSilentMembers(Instant now) {
        if (State.LOBBY == state) {
            List<Member> silentMembers = members.stream()
                    .filter(member -> goneAt(member).isBefore(now))
                    .sorted(Comparator.comparing((Member member) -> member.lastSeenAt))
                    .toList();
            silentMembers.forEach(member -> remove(member, goneAt(member)));
        }
    }

    RoomView view(UUID viewerId) {
        List<RoomView.RoomPlayer> players = members.stream()
                .map(member -> new RoomView.RoomPlayer(member.nick, member.kind, member.isReady,
                        member.playerId.equals(hostId), member.playerId.equals(viewerId)))
                .toList();
        RoomView.Host host = members.isEmpty() ? null : new RoomView.Host(members.getFirst().nick);
        return new RoomView(id, stateName(), host, players, level, shape, Rules.ROOM_SIZE);
    }

    // Slots follow the join order and stay fixed for the race.
    void start(Instant now) {
        state = State.STARTING;
        startingAt = now;
        for (int slot = 0; slot < members.size(); slot++) {
            racers.add(new Racer(members.get(slot), slot));
        }
    }

    boolean canRace(UUID playerId) {
        return State.LOBBY != state && null != racer(playerId);
    }

    // A member connecting again replaces their old socket. A socket that
    // finds the race gone since its handshake was checked is closed.
    void connect(UUID playerId, PlayerSocket socket, Instant now) {
        advance(now);
        Racer racer = canRace(playerId) ? racer(playerId) : null;
        if (null == racer) {
            socket.close(RACE_OVER);
        } else {
            if (racer.isConnected()) {
                racer.socket.close(REPLACED);
            }
            racer.socket = socket;
            racer.lastMessageAt = now;
            racer.goneSince = null;
            socket.send(stateMessage(racer));
            advance(now);
        }
    }

    // Only the member's current socket counts: a replaced or closed one
    // reports its close later, when the member may have a new one.
    void disconnect(UUID playerId, PlayerSocket socket, Instant now) {
        Racer racer = racer(playerId);
        if (null != racer && socket == racer.socket) {
            racer.socket = null;
            racer.goneSince = now;
        }
    }

    void receive(UUID playerId, PlayerSocket socket, RaceMessage message, Instant now) {
        advance(now);
        Racer racer = racer(playerId);
        if (null != racer && socket == racer.socket) {
            racer.lastMessageAt = now;
            switch (message) {
                case RaceMessage.Ping _ -> socket.send(message(TYPE_PONG).encode());
                case RaceMessage.Position position -> move(racer, position, now);
                case RaceMessage.Finish finish -> finish(racer, finish.ticks(), now);
                case RaceMessage.Bad _ -> socket.send(error(BAD_MESSAGE));
            }
            advance(now);
        }
    }

    // The room is gone: its sockets close without results.
    void close() {
        inRace().filter(Racer::isConnected).forEach(racer -> racer.socket.close(RACE_OVER));
    }

    void advance(Instant now) {
        if (State.STARTING == state && null == countdownAt) {
            advanceStart(now);
        }
        if (State.STARTING == state && null != countdownAt && !now.isBefore(countdownAt.plus(Rules.RACE_COUNTDOWN))) {
            go(countdownAt.plus(Rules.RACE_COUNTDOWN));
        }
        if (null != countdownAt) {
            // Rules that fell due after the race's end no longer apply.
            Instant ruleTime = State.RACING == state ? earliest(now, raceEnd()) : now;
            closeIdleSockets(ruleTime);
            leaveGoneRacers(ruleTime);
        }
        if (State.LOBBY != state && inRace().allMatch(Racer::hasFinished)) {
            endRace(lastExitAt);
        } else if (State.RACING == state && !now.isBefore(raceEnd())) {
            endRace(raceEnd());
        }
    }

    // The countdown starts once every member is connected, or when the
    // connect wait ends; a member not connected then counts as left.
    private void advanceStart(Instant now) {
        Instant connectDeadline = startingAt.plus(Rules.RACE_CONNECT_WAIT);
        boolean isWaitOver = !now.isBefore(connectDeadline);
        if (isWaitOver) {
            List<Racer> unconnected = inRace().filter(racer -> !racer.isConnected()).toList();
            unconnected.forEach(racer -> countAsLeft(racer, connectDeadline));
        }
        boolean hasRacers = inRace().findAny().isPresent();
        if (hasRacers && (isWaitOver || inRace().allMatch(Racer::isConnected))) {
            countdownAt = earliest(now, connectDeadline);
            sendToConnected(message(TYPE_COUNTDOWN).put(FIELD_SECONDS, Rules.RACE_COUNTDOWN_SECONDS).encode());
        }
    }

    private void go(Instant at) {
        state = State.RACING;
        goAt = at;
        sendToConnected(message(TYPE_GO).put(FIELD_AT, goAt.toEpochMilli()).encode());
    }

    private Instant raceEnd() {
        Instant cap = goAt.plus(Rules.RACE_CAP);
        return null == firstFinishAt ? cap : earliest(cap, firstFinishAt.plus(Rules.RACE_AFTER_FIRST_FINISH));
    }

    // The client does not reconnect after the idle close, so the member
    // counts as left once the reconnect wait is over.
    private void closeIdleSockets(Instant ruleTime) {
        inRace().filter(Racer::isConnected)
                .filter(racer -> racer.lastMessageAt.plus(Rules.RACE_SOCKET_IDLE).isBefore(ruleTime))
                .forEach(racer -> {
                    racer.socket.close(IDLE);
                    racer.socket = null;
                    racer.goneSince = racer.lastMessageAt.plus(Rules.RACE_SOCKET_IDLE);
                });
    }

    private void leaveGoneRacers(Instant ruleTime) {
        List<Racer> goneRacers = inRace()
                .filter(racer -> !racer.isConnected() && null != racer.goneSince)
                .filter(racer -> racer.goneSince.plus(Rules.RACE_RECONNECT_WAIT).isBefore(ruleTime))
                .sorted(Comparator.comparing((Racer racer) -> racer.goneSince))
                .toList();
        goneRacers.forEach(racer -> countAsLeft(racer, racer.goneSince.plus(Rules.RACE_RECONNECT_WAIT)));
    }

    private void countAsLeft(Racer racer, Instant leftAt) {
        remove(member(racer.playerId), leftAt);
    }

    // The positions are relayed only while racing, and never to the sender.
    private void move(Racer racer, RaceMessage.Position position, Instant now) {
        if (State.RACING != state) {
            racer.socket.send(error(NOT_RACING));
        } else if (!isOverPositionRate(racer, now)) {
            racer.recentPositions.addLast(now);
            racer.position = position;
            String relayed = positionJson(racer).put(RaceMessage.FIELD_TYPE, RaceMessage.TYPE_POS)
                    .put(FIELD_NICK, racer.nick).encode();
            inRace().filter(other -> other != racer && other.isConnected())
                    .forEach(other -> other.socket.send(relayed));
        }
    }

    private static boolean isOverPositionRate(Racer racer, Instant now) {
        Instant windowStart = now.minusSeconds(1);
        racer.recentPositions.removeIf(acceptedAt -> !acceptedAt.isAfter(windowStart));
        return Rules.RACE_POSITIONS_PER_SECOND <= racer.recentPositions.size();
    }

    // The first finish received wins; the ticks are only shown.
    private void finish(Racer racer, long ticks, Instant now) {
        if (State.RACING != state) {
            racer.socket.send(error(NOT_RACING));
        } else if (racer.hasFinished()) {
            racer.socket.send(error(ALREADY_FINISHED));
        } else {
            racer.finishTicks = ticks;
            racer.place = (int) racers.stream().filter(Racer::hasFinished).count();
            firstFinishAt = null == firstFinishAt ? now : firstFinishAt;
            lastExitAt = latest(lastExitAt, now);
        }
    }

    // Back in the lobby every member counts as seen at the end, or a race
    // longer than the lobby's 60 s of silence would empty the room at the
    // next sweep.
    private void endRace(Instant endedAt) {
        String results = resultsMessage();
        inRace().filter(Racer::isConnected).forEach(racer -> {
            racer.socket.send(results);
            racer.socket.close(RACE_OVER);
        });
        racers.clear();
        startingAt = null;
        countdownAt = null;
        goAt = null;
        firstFinishAt = null;
        lastExitAt = null;
        state = State.LOBBY;
        members.forEach(member -> {
            member.isReady = false;
            member.lastSeenAt = latest(member.lastSeenAt, endedAt);
        });
    }

    private void remove(Member member, Instant leftAt) {
        members.remove(member);
        if (members.isEmpty()) {
            emptySince = leftAt;
        } else {
            hostId = members.getFirst().playerId;
        }
        Racer racer = racer(member.playerId);
        if (null != racer) {
            if (racer.isConnected()) {
                racer.socket.close(RACE_OVER);
                racer.socket = null;
            }
            racer.leftAt = leftAt;
            lastExitAt = latest(lastExitAt, leftAt);
        }
    }

    private Racer racer(UUID playerId) {
        return inRace().filter(racer -> playerId.equals(racer.playerId)).findFirst().orElse(null);
    }

    private Stream<Racer> inRace() {
        return racers.stream().filter(Racer::isInRace);
    }

    private void sendToConnected(String message) {
        inRace().filter(Racer::isConnected).forEach(racer -> racer.socket.send(message));
    }

    // The others' last known positions are sent only while racing.
    private String stateMessage(Racer viewer) {
        JsonArray players = new JsonArray();
        inRace().forEach(racer -> players.add(new JsonObject()
                .put(FIELD_SLOT, racer.slot)
                .put(FIELD_NICK, racer.nick)
                .put(Rules.FIELD_KIND, racer.kind)
                .put(FIELD_YOU, racer == viewer)
                .put(FIELD_CONNECTED, racer.isConnected())
                .put(FIELD_FINISHED, racer.hasFinished())));
        JsonArray positions = new JsonArray();
        if (State.RACING == state) {
            inRace().filter(racer -> racer != viewer && null != racer.position)
                    .forEach(racer -> positions.add(positionJson(racer)));
        }
        return message(TYPE_STATE)
                .put(FIELD_STATE, stateName())
                .put(Rules.FIELD_LEVEL, level)
                .put(Rules.FIELD_SHAPE, shape)
                .put(FIELD_PLAYERS, players)
                .put(FIELD_POSITIONS, positions)
                .encode();
    }

    // Finishers in the order received, then the others in slot order.
    private String resultsMessage() {
        JsonArray places = new JsonArray();
        racers.stream()
                .sorted(Comparator.comparing((Racer racer) -> null == racer.place ? Integer.MAX_VALUE : racer.place))
                .forEach(racer -> places.add(new JsonObject()
                        .put(FIELD_SLOT, racer.slot)
                        .put(FIELD_NICK, racer.nick)
                        .put(Rules.FIELD_KIND, racer.kind)
                        .put(FIELD_PLACE, racer.place)
                        .put(Rules.FIELD_TICKS, racer.finishTicks)
                        .put(FIELD_LEFT, !racer.isInRace())));
        return message(TYPE_RESULTS).put(FIELD_PLACES, places).encode();
    }

    private static JsonObject positionJson(Racer racer) {
        return new JsonObject()
                .put(FIELD_SLOT, racer.slot)
                .put(RaceMessage.FIELD_X, racer.position.x())
                .put(RaceMessage.FIELD_Y, racer.position.y())
                .put(RaceMessage.FIELD_TICK, racer.position.tick());
    }

    private static JsonObject message(String type) {
        return new JsonObject().put(RaceMessage.FIELD_TYPE, type);
    }

    private static String error(String code) {
        return message(TYPE_ERROR).put(FIELD_ERROR, code).encode();
    }

    private String stateName() {
        return state.name().toLowerCase(Locale.ROOT);
    }

    private static Instant earliest(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private static Instant latest(Instant first, Instant second) {
        return null == first || first.isBefore(second) ? second : first;
    }

    private static Instant goneAt(Member member) {
        return member.lastSeenAt.plus(Rules.LOBBY_MEMBER_SILENCE);
    }
}
