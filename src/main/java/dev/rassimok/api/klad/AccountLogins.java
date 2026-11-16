package dev.rassimok.api.klad;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestResponse;

import java.time.Instant;

/**
 * Signs a device in to the account of an email address that a login has
 * just proven, by a code or through Google: the same rules and the same
 * answer for both (contract section 5).
 */
@ApplicationScoped
public class AccountLogins {

    @Inject
    Sessions sessions;

    @Inject
    PresenceStore presence;

    @Inject
    RoomStore rooms;

    // Runs in the caller's transaction. The caller decides whether an
    // ApiException from here rolls back its own writes.
    RestResponse.ResponseBuilder<LoginView> signIn(String email, String deviceToken, Instant now) {
        // Two logins at once for an address without an account would both
        // turn their guest into it; the second would fail on the unique email
        // and log the address. Locked, it waits and logs into the new account.
        Player.getEntityManager()
                .createNativeQuery("select 1 from pg_advisory_xact_lock(hashtextextended(?1, 0))")
                .setParameter(1, email)
                .getSingleResult();
        PlayerSession deviceSession = sessions.findValid(deviceToken, now);
        Player devicePlayer = null == deviceSession ? null : Player.findById(deviceSession.playerId);
        Player player = Player.find("email", email).firstResult();
        if (null == player) {
            player = turnGuestIntoAccount(devicePlayer, email);
        }
        // A new token for the new identity; the device's old one stops working.
        if (null != deviceSession) {
            deviceSession.delete();
        }
        boolean isGuestOfSameKind = null != devicePlayer && null == devicePlayer.email
                && player.kind.equals(devicePlayer.kind);
        if (isGuestOfSameKind) {
            mergeGuest(devicePlayer, player);
            presence.forget(devicePlayer.id);
            rooms.forgetDeleted(devicePlayer.id);
        }
        player.lastSeenAt = now;
        String newToken = sessions.create(player, now);
        return RestResponse.ResponseBuilder.ok(LoginView.of(player, isGuestOfSameKind))
                .header(HttpHeaders.SET_COOKIE, Sessions.cookie(newToken));
    }

    private static Player turnGuestIntoAccount(Player devicePlayer, String email) {
        boolean isGuest = null != devicePlayer && null == devicePlayer.email;
        if (!isGuest) {
            throw new ApiException(Response.Status.UNAUTHORIZED, ApiException.NO_SESSION);
        }
        // Computed again, not read: a guest written by an older revision can
        // still hold a key without the lookalike map.
        String nickKey = Nicks.key(devicePlayer.nick);
        if (Player.isNickKeyTaken(nickKey, devicePlayer.id)) {
            throw new ApiException(Response.Status.CONFLICT, ApiException.NICK_TAKEN);
        }
        devicePlayer.nickKey = nickKey;
        devicePlayer.email = email;
        Player.flushNickKey();
        return devicePlayer;
    }

    // The guest's row is locked as a result submission locks it, so a
    // submission already under way is moved too instead of going with the guest.
    private static void mergeGuest(Player guest, Player account) {
        Player.getEntityManager().lock(guest, LockModeType.PESSIMISTIC_WRITE);
        LevelClear.update("playerId = ?1 where playerId = ?2", account.id, guest.id);
        GameRun.update("playerId = ?1 where playerId = ?2", account.id, guest.id);
        guest.delete();
    }
}
