package dev.rassimok.api.klad;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** One room and its lobby. Not thread-safe: RoomStore holds its lock around every use. */
final class Room {

    enum State {
        LOBBY, STARTING, RACING
    }

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

    final String id;
    State state = State.LOBBY;
    int level = Rules.LEVEL_MIN;
    String shape = Rules.SHAPE_LANDSCAPE;
    // In join order; the first one is the host.
    private final List<Member> members = new ArrayList<>();
    // Kept when the room empties, so its last host's next room still closes it.
    private UUID hostId;
    private Instant emptySince;

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

    void remove(Member member, Instant leftAt) {
        members.remove(member);
        if (members.isEmpty()) {
            emptySince = leftAt;
        } else {
            hostId = members.getFirst().playerId;
        }
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
        return new RoomView(id, state.name().toLowerCase(Locale.ROOT), host, players, level, shape, Rules.ROOM_SIZE);
    }

    private static Instant goneAt(Member member) {
        return member.lastSeenAt.plus(Rules.LOBBY_MEMBER_SILENCE);
    }
}
