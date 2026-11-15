package dev.rassimok.api.klad;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.hibernate.Session;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Deletes the guests nobody can reach any more, with their sessions and
 * results, and expired sessions. Cloud Run gives no CPU between requests, so
 * the first API request after a start, and then one a day, carries it.
 */
// Not a singleton: the native build creates the filters, and a singleton
// would be created with them, together with the stores and their
// SecureRandom. The proxy defers it to the first request.
@ApplicationScoped
public class GuestPurge {

    private static final Logger LOG = Logger.getLogger(GuestPurge.class);

    // A guest without a session goes by last_seen_at, which moved with its
    // expiry; sessions and results go by cascade. Oldest first, so a flood
    // cannot hold back older guests. A lock wait re-checks only the outer where.
    private static final String DELETE_GUESTS = """
            delete from player
            where id in (
                select player.id from player
                where player.email is null
                  and not exists (
                      select 1 from session
                      where session.player_id = player.id and session.expires_at > :now)
                  and (exists (select 1 from session where session.player_id = player.id)
                       or player.last_seen_at <= :lastSeenBefore)
                order by player.last_seen_at
                limit :batch)
              and email is null
            returning id
            """;

    // Only accounts': a guest beyond the batch keeps its expired session, and
    // with it its turn in the next run. The outer where re-checks the expiry.
    private static final String DELETE_EXPIRED_SESSIONS = """
            delete from session
            where token_hash in (
                select session.token_hash from session
                join player on player.id = session.player_id
                where player.email is not null and session.expires_at <= :now
                limit :batch)
              and expires_at <= :now
            """;

    record Purged(List<UUID> guestIds, int expiredSessions) {
    }

    @Inject
    Session hibernateSession;

    @Inject
    PresenceStore presence;

    @Inject
    RoomStore rooms;

    private final Clock clock;
    private Instant nextRunAt = Instant.EPOCH;

    GuestPurge() {
        this(Clock.systemUTC());
    }

    GuestPurge(Clock clock) {
        this.clock = clock;
    }

    // Before the request's own work and in a transaction of its own, so a
    // failure leaves the answer alone. Not after the answer: Cloud Run may
    // then freeze the CPU in the middle of the transaction.
    @ServerRequestFilter
    public void purgeOnceADay() {
        if (tryClaimRun()) {
            try {
                Purged purged = purge(clock.instant());
                LOG.infof("Guest purge deleted %d guests and %d expired sessions",
                        purged.guestIds().size(), purged.expiredSessions());
            } catch (RuntimeException e) {
                // A database message can quote key values, which are player ids.
                LOG.errorf("Guest purge failed: %s", e.getClass().getName());
            }
        }
    }

    // The run is claimed before it starts and stays claimed when it fails,
    // so a broken purge waits a day instead of running on every request.
    synchronized boolean tryClaimRun() {
        Instant now = clock.instant();
        boolean isDue = !now.isBefore(nextRunAt);
        if (isDue) {
            nextRunAt = now.plus(Rules.PURGE_INTERVAL);
        }
        return isDue;
    }

    Purged purge(Instant now) {
        Purged purged = QuarkusTransaction.requiringNew().call(() -> deleteExpired(now));
        for (UUID guestId : purged.guestIds()) {
            presence.forget(guestId);
            rooms.forgetDeleted(guestId);
        }
        return purged;
    }

    private Purged deleteExpired(Instant now) {
        // Bound how long the purge holds up the request that carries it: each
        // wait for a row lock to 2 s, each statement, its waits included, to 10 s.
        hibernateSession.createNativeMutationQuery("set local lock_timeout = '2s'").executeUpdate();
        hibernateSession.createNativeMutationQuery("set local statement_timeout = '10s'").executeUpdate();
        List<UUID> guestIds = hibernateSession.createNativeQuery(DELETE_GUESTS, UUID.class)
                .setParameter("lastSeenBefore", now.minus(Sessions.LIFETIME))
                .setParameter("now", now)
                .setParameter("batch", Rules.PURGE_BATCH)
                .getResultList();
        int expiredSessions = hibernateSession.createNativeMutationQuery(DELETE_EXPIRED_SESSIONS)
                .setParameter("now", now)
                .setParameter("batch", Rules.PURGE_BATCH)
                .executeUpdate();
        return new Purged(guestIds, expiredSessions);
    }
}
