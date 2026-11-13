package dev.rassimok.api.klad;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

@ApplicationScoped
public class Sessions {

    static final String COOKIE = "klad_session";
    static final Duration LIFETIME = Duration.ofDays(90);
    // A session is moved forward only once it is a day old, so the cookie
    // and the database row are rewritten at most once a day.
    private static final Duration REFRESH_AFTER = Duration.ofDays(1);
    private static final int TOKEN_BYTES = 32;
    private static final String COOKIE_ATTRIBUTES = "; Path=/klad/api; HttpOnly; Secure; SameSite=Strict; Max-Age=";

    // An instance field, not a static one, so it is seeded when the service
    // starts and not when the native image is built.
    private final SecureRandom random = new SecureRandom();

    record SignedIn(Player player, String token, boolean wasRefreshed) {
    }

    String create(Player player, Instant now) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        PlayerSession session = new PlayerSession();
        session.tokenHash = hash(token);
        session.playerId = player.id;
        session.createdAt = now;
        session.expiresAt = now.plus(LIFETIME);
        session.persist();
        return token;
    }

    boolean isSignedIn(String token) {
        return null != findValid(token, Instant.now());
    }

    SignedIn require(String token) {
        Instant now = Instant.now();
        PlayerSession session = findValid(token, now);
        if (null == session) {
            throw new ApiException(Response.Status.UNAUTHORIZED, ApiException.NO_SESSION);
        }
        Player player = Player.findById(session.playerId);
        boolean shouldRefresh = session.expiresAt.isBefore(now.plus(LIFETIME).minus(REFRESH_AFTER));
        if (shouldRefresh) {
            session.expiresAt = now.plus(LIFETIME);
            player.lastSeenAt = now;
        }
        return new SignedIn(player, token, shouldRefresh);
    }

    static <T> RestResponse<T> withRefreshedCookie(RestResponse.ResponseBuilder<T> response, SignedIn signedIn) {
        if (signedIn.wasRefreshed()) {
            response.header(HttpHeaders.SET_COOKIE, cookie(signedIn.token()));
        }
        return response.build();
    }

    static String cookie(String token) {
        return COOKIE + "=" + token + COOKIE_ATTRIBUTES + LIFETIME.toSeconds();
    }

    static String clearedCookie() {
        return COOKIE + "=" + COOKIE_ATTRIBUTES + 0;
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private PlayerSession findValid(String token, Instant now) {
        PlayerSession session = null == token ? null : PlayerSession.findById(hash(token));
        return null != session && session.expiresAt.isAfter(now) ? session : null;
    }
}
