package dev.rassimok.api.klad;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GoogleLoginsTest {

    private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");
    private static final String SECRET = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_AbCdE";
    private static final String OTHER_SECRET = "ZbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_AbCdE";
    // From openssl dgst -sha256 -hmac SECRET over each label, base64url.
    private static final String STATE = "JATmI6-W3FGpAF255TvletaKBsf40sWTkhIbHoWaVGM";
    private static final String NONCE = "41CEErNLd0Ud0Nu92xR9Vye_tBmnU2QwZRM3yEx0_1M";
    private static final String CODE_VERIFIER = "md5BVyUVCWO-9vZ4lz-agwW-F1mtlvPhnWRXcDBPNQw";
    private static final String CODE_CHALLENGE = "a0PDamKcaCU5gRW51a7ks1NP6Ja-ZUzKqRjhKCCimU0";
    private static final String CLIENT_ID = "123-abc.apps.googleusercontent.com";
    private static final GoogleLogins.Settings SETTINGS = new GoogleLogins.Settings(CLIENT_ID, "client-secret",
            "https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token",
            "https://rassimok.dev/klad/api/login/google/callback", "https://rassimok.dev/klad/");
    private static final String EMAIL = "player@example.com";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final FakeClock clock = new FakeClock(NOW);
    private final GoogleLogins googleLogins = new GoogleLogins(SETTINGS, JSON, clock);

    static Stream<Arguments> refusedClaims() {
        return Stream.of(
                Arguments.of("another issuer", Map.of("iss", "https://evil.example.com")),
                Arguments.of("issuer with a trailing slash", Map.of("iss", "https://accounts.google.com/")),
                Arguments.of("issuer as a number", Map.of("iss", 1)),
                Arguments.of("another audience", Map.of("aud", "other.apps.googleusercontent.com")),
                Arguments.of("audience as a list", Map.of("aud", List.of(CLIENT_ID))),
                Arguments.of("expired a second ago", Map.of("exp", NOW.getEpochSecond() - 1)),
                Arguments.of("expiring now", Map.of("exp", NOW.getEpochSecond())),
                Arguments.of("expiry as a string", Map.of("exp", String.valueOf(NOW.getEpochSecond() + 60))),
                Arguments.of("expiry with a fraction", Map.of("exp", NOW.getEpochSecond() + 60.5)),
                Arguments.of("another nonce", Map.of("nonce", "x" + NONCE.substring(1))),
                Arguments.of("nonce as a number", Map.of("nonce", 7)),
                Arguments.of("unverified", Map.of("email_verified", false)),
                Arguments.of("unverified as a string", Map.of("email_verified", "false")),
                Arguments.of("verified in capitals", Map.of("email_verified", "TRUE")),
                Arguments.of("verified as a number", Map.of("email_verified", 1)),
                Arguments.of("verified as a list", Map.of("email_verified", List.of(true))),
                Arguments.of("invalid address", Map.of("email", "not-an-address")),
                Arguments.of("address with a space", Map.of("email", "a b@example.com")),
                Arguments.of("address over 254 characters", Map.of("email", "a".repeat(243) + "@example.com")),
                Arguments.of("address as a number", Map.of("email", 42)));
    }

    @Test
    void shouldDeriveValues_whenDerive_givenSecret() {
        // when
        List<String> derived = List.of(GoogleLogins.state(SECRET), GoogleLogins.nonce(SECRET),
                GoogleLogins.codeVerifier(SECRET), GoogleLogins.codeChallenge(GoogleLogins.codeVerifier(SECRET)));

        // then
        assertThat(derived, equalTo(List.of(STATE, NONCE, CODE_VERIFIER, CODE_CHALLENGE)));
    }

    @Test
    void shouldDeriveOtherValues_whenDerive_givenOtherSecret() {
        // when
        List<String> derived = List.of(GoogleLogins.state(OTHER_SECRET), GoogleLogins.nonce(OTHER_SECRET),
                GoogleLogins.codeVerifier(OTHER_SECRET));

        // then
        assertThat(derived.contains(STATE) || derived.contains(NONCE) || derived.contains(CODE_VERIFIER),
                equalTo(false));
    }

    // The example of RFC 7636, appendix B.
    @Test
    void shouldMatchSpecificationExample_whenCodeChallenge() {
        // when
        String challenge = GoogleLogins.codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk");

        // then
        assertThat(challenge, equalTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"));
    }

    @Test
    void shouldAskForOpenidAndEmailOnly_whenAuthorizationUrl() {
        // when
        String url = googleLogins.authorizationUrl(SECRET);

        // then
        assertThat(url, equalTo("https://accounts.google.com/o/oauth2/v2/auth?response_type=code"
                + "&client_id=123-abc.apps.googleusercontent.com"
                + "&redirect_uri=https%3A%2F%2Frassimok.dev%2Fklad%2Fapi%2Flogin%2Fgoogle%2Fcallback"
                + "&scope=openid%20email&state=" + STATE + "&nonce=" + NONCE
                + "&code_challenge=" + CODE_CHALLENGE + "&code_challenge_method=S256"));
    }

    @Test
    void shouldMakeRandomSecrets_whenNewSecret() {
        // when
        String first = googleLogins.newSecret();
        String second = googleLogins.newSecret();

        // then
        assertThat(first, matchesPattern("[A-Za-z0-9_-]{43}"));
        assertThat(second, matchesPattern("[A-Za-z0-9_-]{43}"));
        assertThat(first, not(equalTo(second)));
    }

    @Test
    void shouldWriteCookies_whenCookieAndClearedCookie() {
        // when
        List<String> cookies = List.of(GoogleLogins.cookie(SECRET), GoogleLogins.clearedCookie());

        // then
        assertThat(cookies, equalTo(List.of(
                "klad_oauth=" + SECRET + "; Path=/klad/api/login/google; HttpOnly; Secure; SameSite=Lax; Max-Age=600",
                "klad_oauth=; Path=/klad/api/login/google; HttpOnly; Secure; SameSite=Lax; Max-Age=0")));
    }

    @Test
    void shouldSendBackToGame_whenGameUrl() {
        // when
        List<String> urls = List.of(googleLogins.gameUrl(GoogleLogins.Outcome.SIGNED_IN),
                googleLogins.gameUrl(GoogleLogins.Outcome.FAILED), googleLogins.gameUrl(GoogleLogins.Outcome.UNAVAILABLE));

        // then
        assertThat(urls, equalTo(List.of("https://rassimok.dev/klad/#login=google",
                "https://rassimok.dev/klad/#login=google-failed", "https://rassimok.dev/klad/#login=google-unavailable")));
    }

    @Test
    void shouldAcceptReturn_whenIsReturnOf_givenStateOfCookie() {
        // when
        boolean isReturn = GoogleLogins.isReturnOf(SECRET, STATE);

        // then
        assertThat(isReturn, equalTo(true));
    }

    static Stream<Arguments> returnsNotOfCookie() {
        return Stream.of(
                Arguments.of(null, STATE),
                Arguments.of(SECRET, null),
                Arguments.of(OTHER_SECRET, STATE),
                Arguments.of(SECRET, GoogleLogins.state(OTHER_SECRET)),
                Arguments.of(SECRET, NONCE),
                Arguments.of(SECRET, STATE + "A"),
                Arguments.of(SECRET, STATE.substring(1)),
                Arguments.of(SECRET, STATE.repeat(100)),
                Arguments.of(SECRET.substring(1), GoogleLogins.state(SECRET.substring(1))),
                Arguments.of(SECRET + "A", GoogleLogins.state(SECRET + "A")),
                Arguments.of(SECRET.replace('A', '+'), GoogleLogins.state(SECRET.replace('A', '+'))));
    }

    @ParameterizedTest
    @MethodSource("returnsNotOfCookie")
    void shouldRefuseReturn_whenIsReturnOf_givenStateNotOfCookie(String secret, String state) {
        // when
        boolean isReturn = GoogleLogins.isReturnOf(secret, state);

        // then
        assertThat(isReturn, equalTo(false));
    }

    @Test
    void shouldBoundLength_whenIsCode() {
        // when
        List<Boolean> accepted = List.of(GoogleLogins.isCode("4/0Ab"),
                GoogleLogins.isCode("x".repeat(Rules.GOOGLE_CODE_LENGTH_MAX)),
                GoogleLogins.isCode("x".repeat(Rules.GOOGLE_CODE_LENGTH_MAX + 1)), GoogleLogins.isCode(""),
                GoogleLogins.isCode(null));

        // then
        assertThat(accepted, equalTo(List.of(true, true, false, false, false)));
    }

    static Stream<Arguments> incompleteSettings() {
        return Stream.of(
                Arguments.of(null, "client-secret"),
                Arguments.of(CLIENT_ID, null),
                Arguments.of(" ", "client-secret"),
                Arguments.of(CLIENT_ID, ""),
                Arguments.of(null, null));
    }

    @ParameterizedTest
    @MethodSource("incompleteSettings")
    void shouldBeUnavailableAndRefuseReturn_whenAcceptReturn_givenIncompleteSettings(String clientId,
                                                                                    String clientSecret) {
        // given
        GoogleLogins.Settings settings = new GoogleLogins.Settings(clientId, clientSecret,
                SETTINGS.authorizationEndpoint(), SETTINGS.tokenEndpoint(), SETTINGS.redirectUri(), SETTINGS.gameUrl());
        GoogleLogins withoutSettings = new GoogleLogins(settings, JSON, clock);

        // when
        GoogleLogins.Outcome outcome = withoutSettings.acceptReturn(SECRET, STATE, "4/0Ab", null);

        // then
        assertThat(withoutSettings.isAvailable(), equalTo(false));
        assertThat(outcome, equalTo(GoogleLogins.Outcome.UNAVAILABLE));
        assertThat(withoutSettings.tryClaimReturn(SECRET), equalTo(true));
    }

    @Test
    void shouldFailWithoutUsingReturn_whenAcceptReturn_givenErrorFromGoogle() {
        // when
        GoogleLogins.Outcome outcome = googleLogins.acceptReturn(SECRET, STATE, null, "access_denied");

        // then
        assertThat(outcome, equalTo(GoogleLogins.Outcome.FAILED));
        assertThat(googleLogins.tryClaimReturn(SECRET), equalTo(true));
    }

    @Test
    void shouldFailWithoutUsingReturn_whenAcceptReturn_givenStateOfOtherCookie() {
        // when
        GoogleLogins.Outcome outcome = googleLogins.acceptReturn(SECRET, GoogleLogins.state(OTHER_SECRET), "4/0Ab",
                null);

        // then
        assertThat(outcome, equalTo(GoogleLogins.Outcome.FAILED));
        assertThat(googleLogins.tryClaimReturn(SECRET), equalTo(true));
    }

    @Test
    void shouldFailWithoutCall_whenAcceptReturn_givenReturnAlreadyUsed() {
        // given
        googleLogins.tryClaimReturn(SECRET);

        // when
        GoogleLogins.Outcome outcome = googleLogins.acceptReturn(SECRET, STATE, "4/0Ab", null);

        // then
        assertThat(outcome, equalTo(GoogleLogins.Outcome.FAILED));
    }

    @Test
    void shouldReadNormalisedAddress_whenAddressIn_givenValidToken() {
        // given
        Map<String, Object> claims = validClaims();
        claims.put("email", "  Player@Example.COM ");

        // when
        String email = googleLogins.addressIn(token(claims), NONCE, NOW);

        // then
        assertThat(email, equalTo(EMAIL));
    }

    @Test
    void shouldReadAddress_whenAddressIn_givenIssuerWithoutSchemeAndVerifiedAsString() {
        // given
        Map<String, Object> claims = validClaims();
        claims.put("iss", "accounts.google.com");
        claims.put("email_verified", "true");

        // when
        String email = googleLogins.addressIn(token(claims), NONCE, NOW);

        // then
        assertThat(email, equalTo(EMAIL));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedClaims")
    void shouldRefuse_whenAddressIn_givenClaimFails(String name, Map<String, Object> change) {
        // given
        Map<String, Object> claims = validClaims();
        claims.putAll(change);

        // when
        String email = googleLogins.addressIn(token(claims), NONCE, NOW);

        // then
        assertThat(email, nullValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"iss", "aud", "exp", "nonce", "email_verified", "email"})
    void shouldRefuse_whenAddressIn_givenClaimMissing(String claim) {
        // given
        Map<String, Object> claims = validClaims();
        claims.put(claim, null);
        Map<String, Object> withoutClaim = validClaims();
        withoutClaim.remove(claim);

        // when
        String nullClaim = googleLogins.addressIn(token(claims), NONCE, NOW);
        String missingClaim = googleLogins.addressIn(token(withoutClaim), NONCE, NOW);

        // then
        assertThat(nullClaim, nullValue());
        assertThat(missingClaim, nullValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "header.payload", "a.b.c.d", "eyJhbGciOiJSUzI1NiJ9.not+base64url.sig",
            "eyJhbGciOiJSUzI1NiJ9.bm90IGpzb24.sig", "eyJhbGciOiJSUzI1NiJ9.WzFd.sig", "eyJhbGciOiJSUzI1NiJ9.bnVsbA.sig"})
    void shouldRefuse_whenAddressIn_givenMalformedToken(String idToken) {
        // when
        String email = googleLogins.addressIn(idToken, NONCE, NOW);

        // then
        assertThat(email, nullValue());
    }

    @Test
    void shouldGiveAddressOnce_whenTake_givenAddressWaiting() {
        // given
        googleLogins.tryKeepWaiting(SECRET, EMAIL);

        // when
        GoogleLogins.WaitingAddress address = googleLogins.take(SECRET);
        ApiException exception = assertThrows(ApiException.class, () -> googleLogins.take(SECRET));

        // then
        assertThat(address, equalTo(new GoogleLogins.WaitingAddress(EMAIL, NOW.plus(Rules.GOOGLE_LOGIN_LIFETIME))));
        assertThat(exception.code(), equalTo(ApiException.NO_GOOGLE_LOGIN));
    }

    @Test
    void shouldRefuse_whenTake_givenOtherCookieOrNone() {
        // given
        googleLogins.tryKeepWaiting(SECRET, EMAIL);

        // when
        ApiException other = assertThrows(ApiException.class, () -> googleLogins.take(OTHER_SECRET));
        ApiException none = assertThrows(ApiException.class, () -> googleLogins.take(null));

        // then
        assertThat(List.of(other.status().getStatusCode(), none.status().getStatusCode()), equalTo(List.of(400, 400)));
        assertThat(List.of(other.code(), none.code()),
                equalTo(List.of(ApiException.NO_GOOGLE_LOGIN, ApiException.NO_GOOGLE_LOGIN)));
        assertThat(googleLogins.take(SECRET).email(), equalTo(EMAIL));
    }

    @Test
    void shouldWaitAgain_whenGiveBack_givenTakenAddress() {
        // given
        googleLogins.tryKeepWaiting(SECRET, EMAIL);
        GoogleLogins.WaitingAddress taken = googleLogins.take(SECRET);
        clock.advance(Duration.ofMinutes(5));

        // when
        googleLogins.giveBack(SECRET, taken);

        // then
        assertThat(googleLogins.take(SECRET), equalTo(taken));
    }

    @Test
    void shouldGiveAddress_whenTake_givenOneSecondBeforeExpiry() {
        // given
        googleLogins.tryKeepWaiting(SECRET, EMAIL);
        clock.advance(Rules.GOOGLE_LOGIN_LIFETIME.minusSeconds(1));

        // when
        GoogleLogins.WaitingAddress address = googleLogins.take(SECRET);

        // then
        assertThat(address.email(), equalTo(EMAIL));
    }

    @Test
    void shouldRefuse_whenTake_givenAddressExpired() {
        // given
        googleLogins.tryKeepWaiting(SECRET, EMAIL);
        clock.advance(Rules.GOOGLE_LOGIN_LIFETIME);

        // when
        ApiException exception = assertThrows(ApiException.class, () -> googleLogins.take(SECRET));

        // then
        assertThat(exception.code(), equalTo(ApiException.NO_GOOGLE_LOGIN));
    }

    @Test
    void shouldRefuseOneMore_whenTryKeepWaiting_givenStoreFull() {
        // given
        fillWaitingAddresses();

        // when
        boolean hasRoomBefore = googleLogins.hasRoomToWait();
        boolean isKept = googleLogins.tryKeepWaiting(SECRET, EMAIL);

        // then
        assertThat(hasRoomBefore, equalTo(false));
        assertThat(isKept, equalTo(false));
        assertThrows(ApiException.class, () -> googleLogins.take(SECRET));
    }

    @Test
    void shouldMakeRoom_whenTryKeepWaiting_givenFullStoreExpired() {
        // given
        fillWaitingAddresses();
        clock.advance(Rules.GOOGLE_LOGIN_LIFETIME);

        // when
        boolean isKept = googleLogins.tryKeepWaiting(SECRET, EMAIL);

        // then
        assertThat(isKept, equalTo(true));
        assertThat(googleLogins.take(SECRET).email(), equalTo(EMAIL));
    }

    @Test
    void shouldRefuseSecond_whenTryClaimReturn_givenSameCookie() {
        // when
        boolean isFirstClaimed = googleLogins.tryClaimReturn(SECRET);
        boolean isSecondClaimed = googleLogins.tryClaimReturn(SECRET);
        boolean isOtherClaimed = googleLogins.tryClaimReturn(OTHER_SECRET);

        // then
        assertThat(List.of(isFirstClaimed, isSecondClaimed, isOtherClaimed), equalTo(List.of(true, false, true)));
    }

    @Test
    void shouldRefuseUntilExpiry_whenTryClaimReturn_givenUsedReturn() {
        // given
        googleLogins.tryClaimReturn(SECRET);
        clock.advance(Rules.GOOGLE_LOGIN_LIFETIME.minusSeconds(1));
        boolean isClaimedBeforeExpiry = googleLogins.tryClaimReturn(SECRET);
        clock.advance(Duration.ofSeconds(1));

        // when
        boolean isClaimedAtExpiry = googleLogins.tryClaimReturn(SECRET);

        // then
        assertThat(List.of(isClaimedBeforeExpiry, isClaimedAtExpiry), equalTo(List.of(false, true)));
    }

    // Any start gives a cookie, so used returns cost nothing to make; they
    // push out the oldest and never take a waiting address's place.
    @Test
    void shouldForgetOldestAndKeepWaitingAddresses_whenTryClaimReturn_givenRememberedReturnsFull() {
        // given
        googleLogins.tryClaimReturn(SECRET);
        googleLogins.tryKeepWaiting(SECRET, EMAIL);
        for (int i = 1; i < Rules.GOOGLE_RETURNS_REMEMBERED_MAX; i++) {
            googleLogins.tryClaimReturn("used-" + i);
        }
        boolean isNewestRemembered = !googleLogins.tryClaimReturn("used-" + (Rules.GOOGLE_RETURNS_REMEMBERED_MAX - 1));

        // when
        boolean isNewClaimed = googleLogins.tryClaimReturn(OTHER_SECRET);
        boolean isOldestClaimedAgain = googleLogins.tryClaimReturn(SECRET);

        // then
        assertThat(List.of(isNewestRemembered, isNewClaimed, isOldestClaimedAgain), equalTo(List.of(true, true, true)));
        assertThat(googleLogins.hasRoomToWait(), equalTo(true));
        assertThat(googleLogins.take(SECRET).email(), equalTo(EMAIL));
    }

    private void fillWaitingAddresses() {
        for (int i = 0; i < Rules.GOOGLE_LOGINS_WAITING_MAX; i++) {
            assertThat(googleLogins.tryKeepWaiting("waiting-" + i, "player" + i + "@example.com"), equalTo(true));
        }
    }

    private static Map<String, Object> validClaims() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", "https://accounts.google.com");
        claims.put("azp", CLIENT_ID);
        claims.put("aud", CLIENT_ID);
        claims.put("sub", "110169484474386276334");
        claims.put("email", EMAIL);
        claims.put("email_verified", true);
        claims.put("iat", NOW.getEpochSecond() - 5);
        claims.put("exp", NOW.getEpochSecond() + 1);
        claims.put("nonce", NONCE);
        return claims;
    }

    private static String token(Map<String, Object> claims) {
        try {
            return encode("{\"alg\":\"RS256\",\"typ\":\"JWT\"}") + "." + encode(JSON.writeValueAsString(claims))
                    + ".c2lnbmF0dXJl";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String encode(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
