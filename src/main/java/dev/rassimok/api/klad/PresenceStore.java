package dev.rassimok.api.klad;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.Response;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The players seen by a heartbeat in the last 60 s, kept in the memory of the
 * one instance. Old entries go on every access; nothing runs in between.
 */
@Singleton
public class PresenceStore {

    record OnlinePlayer(String nick, @JsonProperty("guest") boolean isGuest, String kind) {
    }

    record PresenceView(List<OnlinePlayer> players) {
    }

    private record Entry(LobbyPlayer player, Instant lastSeenAt) {
    }

    private final Clock clock;
    // In the order the players came online: a heartbeat replaces the value
    // and keeps its place.
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    PresenceStore() {
        this(Clock.systemUTC());
    }

    PresenceStore(Clock clock) {
        this.clock = clock;
    }

    synchronized void heartbeat(LobbyPlayer player) {
        Instant now = clock.instant();
        removeExpired(now);
        boolean isNew = !entries.containsKey(player.id());
        if (isNew && Rules.PRESENCE_MAX <= entries.size()) {
            throw new ApiException(Response.Status.TOO_MANY_REQUESTS, ApiException.RATE_LIMITED);
        }
        entries.put(player.id(), new Entry(player, now));
    }

    synchronized PresenceView online() {
        removeExpired(clock.instant());
        List<OnlinePlayer> players = entries.values().stream()
                .map(entry -> new OnlinePlayer(entry.player().nick(), entry.player().isGuest(), entry.player().kind()))
                .toList();
        return new PresenceView(players);
    }

    private void removeExpired(Instant now) {
        entries.values().removeIf(entry -> entry.lastSeenAt().plus(Rules.PRESENCE_WINDOW).isBefore(now));
    }
}
