package dev.rassimok.api.klad;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Sign-in with Google: the authorization code flow with PKCE. The start
 * stores nothing; the temporary cookie holds the only secret, and the state,
 * the nonce and the PKCE verifier are derived from it one way. After Google's
 * return the verified address waits in memory until the game finishes the
 * sign-in, at most 10 minutes. Nothing from Google is stored or logged.
 */
@ApplicationScoped
public class GoogleLogins {

    static final String COOKIE = "klad_oauth";
    static final String SCOPE = "openid email";
    // A base64url SHA-256, as every derived value.
    static final int DERIVED_LENGTH = 43;
    static final String CLAIM_ISSUER = "iss";
    static final String CLAIM_AUDIENCE = "aud";
    static final String CLAIM_EXPIRY = "exp";
    static final String CLAIM_NONCE = "nonce";
    static final String CLAIM_EMAIL_VERIFIED = "email_verified";
    static final String CLAIM_EMAIL = "email";
    private static final String PAYLOAD = "payload";
    private static final String ID_TOKEN = "id_token";
    private static final Set<String> ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");
    private static final String COOKIE_ATTRIBUTES =
            "; Path=/klad/api/login/google; HttpOnly; Secure; SameSite=Lax; Max-Age=";
    private static final int SECRET_BYTES = 32;
    private static final Pattern SECRET = Pattern.compile("[A-Za-z0-9_-]{" + DERIVED_LENGTH + "}");
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();
    private static final Logger LOG = Logger.getLogger(GoogleLogins.class);

    /** Where a sign-in sends the player back to: the game, with #login=<hash>. */
    enum Outcome {
        SIGNED_IN("google"),
        FAILED("google-failed"),
        UNAVAILABLE("google-unavailable");

        final String hash;

        Outcome(String hash) {
            this.hash = hash;
        }
    }

    /** Client id and secret are null when not set; then the sign-in is off. */
    record Settings(String clientId, String clientSecret, String authorizationEndpoint, String tokenEndpoint,
                    String redirectUri, String gameUrl) {

        boolean isComplete() {
            return null != clientId && !clientId.isBlank() && null != clientSecret && !clientSecret.isBlank();
        }
    }

    record WaitingAddress(String email, Instant expiresAt) {
    }

    private final Settings settings;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    // Instance fields, set in the constructor and never static, so they are
    // created when the service runs and not when the native image is built.
    private final SecureRandom random;
    private final HttpClient httpClient;
    private final Semaphore tokenCalls;
    // Both keyed by the SHA-256 of the cookie's secret.
    private final Map<String, WaitingAddress> waitingAddresses;
    // In the order the returns came, so the oldest makes way when it is full.
    private final Map<String, Instant> usedReturns;

    @Inject
    GoogleLogins(@ConfigProperty(name = "klad.google.client-id") Optional<String> clientId,
                 @ConfigProperty(name = "klad.google.client-secret") Optional<String> clientSecret,
                 @ConfigProperty(name = "klad.google.authorization-endpoint") String authorizationEndpoint,
                 @ConfigProperty(name = "klad.google.token-endpoint") String tokenEndpoint,
                 @ConfigProperty(name = "klad.google.redirect-uri") String redirectUri,
                 @ConfigProperty(name = "klad.game-url") String gameUrl,
                 ObjectMapper objectMapper) {
        this(new Settings(clientId.orElse(null), clientSecret.orElse(null), authorizationEndpoint, tokenEndpoint,
                redirectUri, gameUrl), objectMapper, Clock.systemUTC());
    }

    GoogleLogins(Settings settings, ObjectMapper objectMapper, Clock clock) {
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.random = new SecureRandom();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Rules.GOOGLE_CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.tokenCalls = new Semaphore(Rules.GOOGLE_TOKEN_CALLS_PER_INSTANCE);
        this.waitingAddresses = new HashMap<>();
        this.usedReturns = new LinkedHashMap<>();
    }

    boolean isAvailable() {
        return settings.isComplete();
    }

    String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        return BASE64URL.encodeToString(bytes);
    }

    // No access_type and no prompt: Google then issues no refresh token.
    String authorizationUrl(String secret) {
        return settings.authorizationEndpoint()
                + "?response_type=code"
                + "&client_id=" + encode(settings.clientId())
                + "&redirect_uri=" + encode(settings.redirectUri())
                + "&scope=" + encode(SCOPE)
                + "&state=" + state(secret)
                + "&nonce=" + nonce(secret)
                + "&code_challenge=" + codeChallenge(codeVerifier(secret))
                + "&code_challenge_method=S256";
    }

    String gameUrl(Outcome outcome) {
        return settings.gameUrl() + "#login=" + outcome.hash;
    }

    static String cookie(String secret) {
        return COOKIE + "=" + secret + COOKIE_ATTRIBUTES + Rules.GOOGLE_LOGIN_LIFETIME.toSeconds();
    }

    static String clearedCookie() {
        return COOKIE + "=" + COOKIE_ATTRIBUTES + 0;
    }

    static String state(String secret) {
        return derive("state", secret);
    }

    static String nonce(String secret) {
        return derive("nonce", secret);
    }

    static String codeVerifier(String secret) {
        return derive("code_verifier", secret);
    }

    static String codeChallenge(String codeVerifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return BASE64URL.encodeToString(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // The return must carry the state of this browser's cookie. Checked
    // before anything is called; the state's length is not secret.
    static boolean isReturnOf(String secret, String state) {
        return null != secret && SECRET.matcher(secret).matches()
                && null != state && DERIVED_LENGTH == state.length()
                && MessageDigest.isEqual(state(secret).getBytes(StandardCharsets.US_ASCII),
                state.getBytes(StandardCharsets.US_ASCII));
    }

    static boolean isCode(String code) {
        return null != code && !code.isEmpty() && Rules.GOOGLE_CODE_LENGTH_MAX >= code.length();
    }

    // Google's return. The address waits only when Google proved it; every
    // failure ends the sign-in, and the browser starts again.
    Outcome acceptReturn(String secret, String state, String code, String error) {
        Outcome outcome = Outcome.UNAVAILABLE;
        if (isAvailable()) {
            boolean isFirstGoodReturn = null == error && isReturnOf(secret, state) && isCode(code)
                    && tryClaimReturn(secret);
            outcome = isFirstGoodReturn ? exchange(secret, code) : Outcome.FAILED;
        }
        return outcome;
    }

    private Outcome exchange(String secret, String code) {
        Outcome outcome = Outcome.UNAVAILABLE;
        if (hasRoomToWait() && tokenCalls.tryAcquire()) {
            String email;
            try {
                email = verifiedAddress(secret, code);
            } finally {
                tokenCalls.release();
            }
            if (null == email) {
                outcome = Outcome.FAILED;
            } else if (tryKeepWaiting(secret, email)) {
                outcome = Outcome.SIGNED_IN;
            }
        } else {
            LOG.warn("Google sign-in refused: busy");
        }
        return outcome;
    }

    // The tokens go out of scope here: only the address is kept.
    private String verifiedAddress(String secret, String code) {
        String idToken = idTokenFor(code, codeVerifier(secret));
        return null == idToken ? null : addressIn(idToken, nonce(secret), clock.instant());
    }

    // The whole call, the answer's body included, is bounded, so a slow
    // Google holds a request thread for at most the timeout.
    private String idTokenFor(String code, String codeVerifier) {
        String form = "grant_type=authorization_code"
                + "&code=" + encode(code)
                + "&client_id=" + encode(settings.clientId())
                + "&client_secret=" + encode(settings.clientSecret())
                + "&redirect_uri=" + encode(settings.redirectUri())
                + "&code_verifier=" + codeVerifier;
        HttpRequest request = HttpRequest.newBuilder(URI.create(settings.tokenEndpoint()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .timeout(Rules.GOOGLE_TOKEN_CALL_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        CompletableFuture<HttpResponse<String>> call =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        String idToken = null;
        try {
            HttpResponse<String> response = call.get(Rules.GOOGLE_TOKEN_CALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (Response.Status.OK.getStatusCode() == response.statusCode()) {
                idToken = JsonFields.text(objectMapper.readTree(response.body()), ID_TOKEN);
            }
            if (null == idToken) {
                LOG.warnf("Google token call failed: status %d", response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warnf("Google token call failed: %s", e.getClass().getName());
        } catch (ExecutionException | TimeoutException | IOException e) {
            // Only the class: a message could quote the request or the answer.
            call.cancel(true);
            LOG.warnf("Google token call failed: %s", LoginResource.rootCause(e).getClass().getName());
        }
        return idToken;
    }

    // The ID token comes straight from Google's token endpoint over TLS, so
    // TLS stands in for its signature; the claims are still checked.
    String addressIn(String idToken, String expectedNonce, Instant now) {
        JsonNode claims = claimsOf(idToken);
        String refusedClaim = refusedClaim(claims, expectedNonce, now);
        if (null != refusedClaim) {
            LOG.warnf("Google ID token refused: %s", refusedClaim);
        }
        return null == refusedClaim ? Emails.normalize(JsonFields.text(claims, CLAIM_EMAIL)) : null;
    }

    private JsonNode claimsOf(String idToken) {
        String[] parts = idToken.split("\\.", -1);
        JsonNode claims = null;
        if (3 == parts.length) {
            try {
                claims = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
            } catch (IllegalArgumentException | IOException e) {
                claims = null;
            }
        }
        return claims;
    }

    // The name of the first claim that fails, or null when all pass.
    private String refusedClaim(JsonNode claims, String expectedNonce, Instant now) {
        String issuer = JsonFields.text(claims, CLAIM_ISSUER);
        String nonce = JsonFields.text(claims, CLAIM_NONCE);
        String refused = null;
        if (null == claims || !claims.isObject()) {
            refused = PAYLOAD;
        } else if (null == issuer || !ISSUERS.contains(issuer)) {
            refused = CLAIM_ISSUER;
        } else if (!settings.clientId().equals(JsonFields.text(claims, CLAIM_AUDIENCE))) {
            refused = CLAIM_AUDIENCE;
        } else if (!isInFuture(claims.get(CLAIM_EXPIRY), now)) {
            refused = CLAIM_EXPIRY;
        } else if (null == nonce || !MessageDigest.isEqual(expectedNonce.getBytes(StandardCharsets.US_ASCII),
                nonce.getBytes(StandardCharsets.US_ASCII))) {
            refused = CLAIM_NONCE;
        } else if (!isTrue(claims.get(CLAIM_EMAIL_VERIFIED))) {
            refused = CLAIM_EMAIL_VERIFIED;
        } else if (!Emails.isValid(Emails.normalize(JsonFields.text(claims, CLAIM_EMAIL)))) {
            refused = CLAIM_EMAIL;
        }
        return refused;
    }

    private static boolean isInFuture(JsonNode seconds, Instant now) {
        return null != seconds && seconds.isIntegralNumber() && seconds.canConvertToLong()
                && now.getEpochSecond() < seconds.longValue();
    }

    // Google's own example writes the flag as the string "true".
    private static boolean isTrue(JsonNode flag) {
        return null != flag && (flag.isBoolean() && flag.booleanValue()
                || flag.isTextual() && "true".equals(flag.textValue()));
    }

    // A return is used once: a second one with the same cookie is refused
    // before any call to Google, also after a failed first one.
    synchronized boolean tryClaimReturn(String secret) {
        Instant now = clock.instant();
        removeExpired(now);
        String key = Sessions.hash(secret);
        boolean isFirst = !usedReturns.containsKey(key);
        if (isFirst) {
            if (Rules.GOOGLE_RETURNS_REMEMBERED_MAX <= usedReturns.size()) {
                Iterator<String> oldest = usedReturns.keySet().iterator();
                oldest.next();
                oldest.remove();
            }
            usedReturns.put(key, now.plus(Rules.GOOGLE_LOGIN_LIFETIME));
        }
        return isFirst;
    }

    synchronized boolean hasRoomToWait() {
        removeExpired(clock.instant());
        return Rules.GOOGLE_LOGINS_WAITING_MAX > waitingAddresses.size();
    }

    synchronized boolean tryKeepWaiting(String secret, String email) {
        Instant now = clock.instant();
        removeExpired(now);
        boolean hasRoom = Rules.GOOGLE_LOGINS_WAITING_MAX > waitingAddresses.size();
        if (hasRoom) {
            waitingAddresses.put(Sessions.hash(secret), new WaitingAddress(email, now.plus(Rules.GOOGLE_LOGIN_LIFETIME)));
        }
        return hasRoom;
    }

    // The address leaves the store while the finish runs, so a second finish
    // with the same cookie finds none; giveBack returns it after an error.
    synchronized WaitingAddress take(String secret) {
        removeExpired(clock.instant());
        WaitingAddress address = null == secret ? null : waitingAddresses.remove(Sessions.hash(secret));
        if (null == address) {
            throw new ApiException(Response.Status.BAD_REQUEST, ApiException.NO_GOOGLE_LOGIN);
        }
        return address;
    }

    synchronized void giveBack(String secret, WaitingAddress address) {
        waitingAddresses.put(Sessions.hash(secret), address);
    }

    private void removeExpired(Instant now) {
        waitingAddresses.values().removeIf(address -> !now.isBefore(address.expiresAt()));
        usedReturns.values().removeIf(expiresAt -> !now.isBefore(expiresAt));
    }

    // HMAC keyed by the secret: one way, and the values say nothing about
    // each other, so the state and nonce can travel in URLs.
    private static String derive(String label, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
            return BASE64URL.encodeToString(mac.doFinal(label.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
