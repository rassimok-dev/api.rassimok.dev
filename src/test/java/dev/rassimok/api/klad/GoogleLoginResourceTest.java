package dev.rassimok.api.klad;

import com.fasterxml.jackson.core.JsonParseException;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiFunction;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;

/**
 * The three Google endpoints against the fake Google. JVM only: the call
 * counter, the log handler and the injected data source need the
 * application in this JVM; GoogleLoginRoundTripIT covers the binary.
 */
@QuarkusTest
@WithTestResource(FakeGoogle.class)
class GoogleLoginResourceTest {

    private static final String CONTENT_SECURITY_POLICY = "default-src 'none'; frame-ancestors 'none'";
    private static final Pattern MAILED_CODE = Pattern.compile("(?<![0-9])[0-9]{6}(?![0-9])");
    private static final String PLAYER_ROW = """
            select id, nick, nick_key, kind, email, created_at, now() - last_seen_at < interval '1 minute'
            from player where id = ?
            """;

    @Inject
    DataSource dataSource;

    @Inject
    MockMailbox mailbox;

    @ConfigProperty(name = "klad.google.token-endpoint")
    String tokenEndpoint;

    // The send log is shared by every test class, and the daily limit counts
    // all of it.
    @BeforeEach
    @AfterEach
    void clearSendLog() throws SQLException {
        TestSql.execute(dataSource, "delete from login_mail");
    }

    static Stream<Arguments> returnsNotOfThisBrowser() {
        return Stream.of(
                badReturn("no cookie", (started, code) -> GoogleSignIns.returnFromGoogle(null,
                        Map.of("state", started.state(), "code", code))),
                badReturn("state of another start", (started, code) -> GoogleSignIns.returnFromGoogle(
                        started.secret(), Map.of("state", GoogleSignIns.started().state(), "code", code))),
                badReturn("no state", (started, code) -> GoogleSignIns.returnFromGoogle(started.secret(),
                        Map.of("code", code))),
                badReturn("state too long", (started, code) -> GoogleSignIns.returnFromGoogle(started.secret(),
                        Map.of("state", started.state() + "A", "code", code))),
                badReturn("error from Google", (started, code) -> GoogleSignIns.returnFromGoogle(started.secret(),
                        Map.of("state", started.state(), "error", "access_denied"))),
                badReturn("error from Google with a code", (started, code) -> GoogleSignIns.returnFromGoogle(
                        started.secret(), Map.of("state", started.state(), "code", code, "error", "server_error"))),
                badReturn("no code", (started, code) -> GoogleSignIns.returnFromGoogle(started.secret(),
                        Map.of("state", started.state()))),
                badReturn("code too long", (started, code) -> GoogleSignIns.returnFromGoogle(started.secret(),
                        Map.of("state", started.state(), "code", "x".repeat(Rules.GOOGLE_CODE_LENGTH_MAX + 1)))));
    }

    static Stream<Arguments> answersThatProveNothing() {
        return Stream.of(
                Arguments.of(Named.of("an error", FakeGoogle.ANSWER_ERROR), true),
                Arguments.of(Named.of("not JSON", FakeGoogle.ANSWER_NOT_JSON), true),
                Arguments.of(Named.of("an unverified address", FakeGoogle.ANSWER_TOKEN), false),
                Arguments.of(Named.of("an address verified as the string false", FakeGoogle.ANSWER_TOKEN), "false"));
    }

    @Test
    void shouldRedirectToGoogleAndSetCookie_whenStart() {
        // given
        int callsBefore = callsToGoogle();

        // when
        ExtractableResponse<Response> response = GoogleSignIns.start();

        // then
        assertThat(response.statusCode(), equalTo(302));
        assertSecurityHeaders(response);
        List<String> cookies = response.headers().getValues("Set-Cookie");
        assertThat(cookies.size(), equalTo(1));
        String secret = GoogleSignIns.secretIn(cookies.getFirst());
        assertThat(response.header("Location"), equalTo("https://accounts.google.com/o/oauth2/v2/auth"
                + "?response_type=code&client_id=fake-client.apps.googleusercontent.com"
                + "&redirect_uri=https%3A%2F%2Frassimok.dev%2Fklad%2Fapi%2Flogin%2Fgoogle%2Fcallback"
                + "&scope=openid%20email&state=" + GoogleLogins.state(secret) + "&nonce=" + GoogleLogins.nonce(secret)
                + "&code_challenge=" + GoogleLogins.codeChallenge(GoogleLogins.codeVerifier(secret))
                + "&code_challenge_method=S256"));
        assertThat(response.body().asString(), equalTo(""));
        assertThat(callsToGoogle(), equalTo(callsBefore));
    }

    @Test
    void shouldGiveEachStartItsOwnCookie_whenStart_givenTwoStarts() {
        // when
        GoogleSignIns.Started first = GoogleSignIns.started();
        GoogleSignIns.Started second = GoogleSignIns.started();

        // then
        assertThat(second.secret(), not(equalTo(first.secret())));
        assertThat(second.state(), not(equalTo(first.state())));
    }

    @Test
    void shouldSendBackToGameAndSetCookieAgain_whenReturnFromGoogle_givenVerifiedAddress() {
        // given
        GoogleSignIns.Started started = GoogleSignIns.started();
        int callsBefore = callsToGoogle();

        // when
        ExtractableResponse<Response> response = GoogleSignIns.returnFromGoogle(started,
                started.code(TestPlayers.uniqueEmail("google-return"))).extract();

        // then
        assertThat(response.statusCode(), equalTo(302));
        assertSecurityHeaders(response);
        assertThat(response.header("Location"), equalTo(GoogleSignIns.SIGNED_IN));
        assertThat(response.headers().getValues("Set-Cookie"), equalTo(List.of(GoogleLogins.cookie(started.secret()))));
        assertThat(response.body().asString(), equalTo(""));
        assertThat(callsToGoogle(), equalTo(callsBefore + 1));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("returnsNotOfThisBrowser")
    void shouldSignNobodyInWithoutCall_whenReturnFromGoogle_givenReturnNotOfThisBrowser(
            BiFunction<GoogleSignIns.Started, String, ValidatableResponse> sendReturn) {
        // given
        GoogleSignIns.Started started = GoogleSignIns.started();
        String code = started.code(TestPlayers.uniqueEmail("google-refused"));
        int callsBefore = callsToGoogle();

        // when
        ValidatableResponse response = sendReturn.apply(started, code);

        // then
        response.statusCode(302).header("Location", GoogleSignIns.FAILED)
                .header("Set-Cookie", GoogleSignIns.CLEARED_COOKIE);
        assertThat(callsToGoogle(), equalTo(callsBefore));
        GoogleSignIns.finish(started.secret(), null)
                .statusCode(400).body("", equalTo(Map.of("error", ApiException.NO_GOOGLE_LOGIN)));
    }

    @Test
    void shouldRefuseWithoutCall_whenReturnFromGoogle_givenReturnAlreadyUsed() {
        // given
        String email = TestPlayers.uniqueEmail("google-replay");
        GoogleSignIns.Started started = GoogleSignIns.started();
        String code = started.code(email);
        GoogleSignIns.returnFromGoogle(started, code).statusCode(302).header("Location", GoogleSignIns.SIGNED_IN);
        int callsBefore = callsToGoogle();

        // when
        ValidatableResponse replayed = GoogleSignIns.returnFromGoogle(started, code);

        // then
        replayed.statusCode(302).header("Location", GoogleSignIns.FAILED)
                .header("Set-Cookie", GoogleSignIns.CLEARED_COOKIE);
        assertThat(callsToGoogle(), equalTo(callsBefore));
    }

    @Test
    void shouldRefuseWithoutCall_whenReturnFromGoogle_givenFirstReturnFailedAtGoogle() {
        // given
        GoogleSignIns.Started started = GoogleSignIns.started();
        String code = started.code(TestPlayers.uniqueEmail("google-failed-replay"), true, FakeGoogle.ANSWER_ERROR);
        GoogleSignIns.returnFromGoogle(started, code).statusCode(302).header("Location", GoogleSignIns.FAILED);
        int callsBefore = callsToGoogle();

        // when
        ValidatableResponse replayed = GoogleSignIns.returnFromGoogle(started, code);

        // then
        replayed.statusCode(302).header("Location", GoogleSignIns.FAILED);
        assertThat(callsToGoogle(), equalTo(callsBefore));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("answersThatProveNothing")
    void shouldSignNobodyIn_whenReturnFromGoogle_givenAnswerThatProvesNothing(String answer, Object emailVerified)
            throws SQLException {
        // given
        String email = TestPlayers.uniqueEmail("google-unproven");
        GoogleSignIns.Started started = GoogleSignIns.started();
        int callsBefore = callsToGoogle();

        // when
        ValidatableResponse response = GoogleSignIns.returnFromGoogle(started,
                started.code(email, emailVerified, answer));

        // then
        response.statusCode(302).header("Location", GoogleSignIns.FAILED)
                .header("Set-Cookie", GoogleSignIns.CLEARED_COOKIE);
        assertThat(callsToGoogle(), equalTo(callsBefore + 1));
        GoogleSignIns.finish(started.secret(), null)
                .statusCode(400).body("", equalTo(Map.of("error", ApiException.NO_GOOGLE_LOGIN)));
        assertThat(TestSql.tablesHolding(dataSource, email), equalTo(List.of()));
    }

    @Test
    void shouldSignNobodyIn_whenReturnFromGoogle_givenTokenForAnotherNonce() {
        // given
        GoogleSignIns.Started started = GoogleSignIns.started();
        String code = FakeGoogle.code(TestPlayers.uniqueEmail("google-nonce"), true,
                GoogleLogins.nonce(GoogleSignIns.started().secret()), started.query().get("code_challenge"),
                FakeGoogle.ANSWER_TOKEN);

        // when
        ValidatableResponse response = GoogleSignIns.returnFromGoogle(started, code);

        // then
        response.statusCode(302).header("Location", GoogleSignIns.FAILED);
        GoogleSignIns.finish(started.secret(), null).statusCode(400);
    }

    // The fake checks the PKCE verifier against the code's challenge, as
    // Google does, and answers 400 otherwise.
    @Test
    void shouldSignNobodyIn_whenReturnFromGoogle_givenCodeIssuedForAnotherStart() {
        // given
        GoogleSignIns.Started started = GoogleSignIns.started();
        GoogleSignIns.Started other = GoogleSignIns.started();
        String code = other.code(TestPlayers.uniqueEmail("google-pkce"));

        // when
        ValidatableResponse response = GoogleSignIns.returnFromGoogle(started, code);

        // then
        response.statusCode(302).header("Location", GoogleSignIns.FAILED);
    }

    @Test
    void shouldFailWithinTimeout_whenReturnFromGoogle_givenGoogleTooSlow() {
        // given
        GoogleSignIns.Started started = GoogleSignIns.started();
        String code = started.code(TestPlayers.uniqueEmail("google-slow"), true, FakeGoogle.ANSWER_SLOW);
        long startedAt = System.nanoTime();

        // when
        ValidatableResponse response = GoogleSignIns.returnFromGoogle(started, code);

        // then
        Duration took = Duration.ofNanos(System.nanoTime() - startedAt);
        response.statusCode(302).header("Location", GoogleSignIns.FAILED);
        assertThat(took, greaterThanOrEqualTo(Rules.GOOGLE_TOKEN_CALL_TIMEOUT));
        assertThat(took, lessThan(FakeGoogle.SLOW_ANSWER));
    }

    @Test
    void shouldAnswerUnavailableAtOnce_whenReturnFromGoogle_givenEveryTokenCallTaken() throws Exception {
        // given
        int callsBefore = callsToGoogle();
        List<Future<ValidatableResponse>> slowReturns = new ArrayList<>();
        ValidatableResponse extra;
        Duration took;
        try (ExecutorService executor = Executors.newFixedThreadPool(Rules.GOOGLE_TOKEN_CALLS_PER_INSTANCE)) {
            for (int i = 0; i < Rules.GOOGLE_TOKEN_CALLS_PER_INSTANCE; i++) {
                GoogleSignIns.Started started = GoogleSignIns.started();
                String code = started.code(TestPlayers.uniqueEmail("google-busy"), true, FakeGoogle.ANSWER_SLOW);
                slowReturns.add(executor.submit(() -> GoogleSignIns.returnFromGoogle(started, code)));
            }
            awaitCallsToGoogle(callsBefore + Rules.GOOGLE_TOKEN_CALLS_PER_INSTANCE);
            GoogleSignIns.Started started = GoogleSignIns.started();
            long startedAt = System.nanoTime();

            // when
            extra = GoogleSignIns.returnFromGoogle(started, started.code(TestPlayers.uniqueEmail("google-extra")));
            took = Duration.ofNanos(System.nanoTime() - startedAt);
        }

        // then
        extra.statusCode(302).header("Location", GoogleSignIns.UNAVAILABLE)
                .header("Set-Cookie", GoogleSignIns.CLEARED_COOKIE);
        assertThat(took, lessThan(Duration.ofSeconds(2)));
        assertThat(callsToGoogle(), equalTo(callsBefore + Rules.GOOGLE_TOKEN_CALLS_PER_INSTANCE));
        for (Future<ValidatableResponse> slowReturn : slowReturns) {
            slowReturn.get().statusCode(302).header("Location", GoogleSignIns.FAILED);
        }
    }

    @Test
    void shouldTurnGuestIntoAccountKeepingNickKindAndResults_whenFinish_givenNewAddressAndGuest()
            throws SQLException {
        // given
        String nick = TestPlayers.uniqueNick("Becomer");
        String guestToken = TestPlayers.createGuest(nick, Rules.KIND_AGENT);
        UUID guestId = playerId(guestToken);
        submitRun(guestToken);
        submitClear(guestToken);
        String email = TestPlayers.uniqueEmail("google-becomer");
        List<Object> expectedPlayer = TestSql.row(dataSource, PLAYER_ROW, guestId);
        expectedPlayer.set(4, email);
        List<List<Object>> expectedResults = resultsOf(guestId);
        String secret = GoogleSignIns.provenAtGoogle(email);

        // when
        ExtractableResponse<Response> response = GoogleSignIns.finish(secret, guestToken).extract();

        // then
        assertThat(response.statusCode(), equalTo(200));
        assertSecurityHeaders(response);
        assertThat(response.as(LoginView.class), equalTo(new LoginView(nick, Rules.KIND_AGENT, true, email, false)));
        String accountToken = GoogleSignIns.sessionTokenIn(response);
        assertThat(response.headers().getValues("Set-Cookie"), equalTo(List.of(Sessions.cookie(accountToken),
                GoogleSignIns.CLEARED_COOKIE)));
        assertThat(playerId(accountToken), equalTo(guestId));
        assertThat(TestSql.row(dataSource, PLAYER_ROW, guestId), equalTo(expectedPlayer));
        assertThat(resultsOf(guestId), equalTo(expectedResults));
        given().cookie(TestPlayers.COOKIE, guestToken).when().get("/players/me").then().statusCode(401);
    }

    @Test
    void shouldMergeGuest_whenFinish_givenAccountMadeByCodeAndGuestOfSameKind() throws SQLException {
        // given
        String email = TestPlayers.uniqueEmail("google-merge");
        String accountNick = TestPlayers.uniqueNick("CodeMade");
        UUID accountId = playerId(accountByCode(accountNick, Rules.KIND_HUMAN, email));
        String guestToken = TestPlayers.createGuest(TestPlayers.uniqueNick("Merged"), Rules.KIND_HUMAN);
        UUID guestId = playerId(guestToken);
        submitRun(guestToken);
        List<List<Object>> guestResults = resultsOf(guestId);
        String secret = GoogleSignIns.provenAtGoogle(email);

        // when
        ExtractableResponse<Response> response = GoogleSignIns.finish(secret, guestToken).extract();

        // then
        assertThat(response.statusCode(), equalTo(200));
        assertThat(response.as(LoginView.class),
                equalTo(new LoginView(accountNick, Rules.KIND_HUMAN, true, email, true)));
        assertThat(playerId(GoogleSignIns.sessionTokenIn(response)), equalTo(accountId));
        assertThat(resultsOf(accountId), equalTo(movedTo(accountId, guestResults)));
        assertThat(TestSql.count(dataSource, "select count(*) from player where id = ?", guestId), equalTo(0L));
    }

    @Test
    void shouldLeaveGuest_whenFinish_givenAccountMadeByCodeAndGuestOfOtherKind() throws SQLException {
        // given
        String email = TestPlayers.uniqueEmail("google-other-kind");
        String accountNick = TestPlayers.uniqueNick("CodeMade");
        UUID accountId = playerId(accountByCode(accountNick, Rules.KIND_HUMAN, email));
        String guestToken = TestPlayers.createGuest(TestPlayers.uniqueNick("Other"), Rules.KIND_AGENT);
        UUID guestId = playerId(guestToken);
        submitRun(guestToken);
        List<Object> expectedGuest = TestSql.row(dataSource, TestPlayers.PLAYER_ROW, guestId);
        List<List<Object>> guestResults = resultsOf(guestId);
        String secret = GoogleSignIns.provenAtGoogle(email);

        // when
        ExtractableResponse<Response> response = GoogleSignIns.finish(secret, guestToken).extract();

        // then
        assertThat(response.statusCode(), equalTo(200));
        assertThat(response.as(LoginView.class),
                equalTo(new LoginView(accountNick, Rules.KIND_HUMAN, true, email, false)));
        assertThat(playerId(GoogleSignIns.sessionTokenIn(response)), equalTo(accountId));
        assertThat(TestSql.row(dataSource, TestPlayers.PLAYER_ROW, guestId), equalTo(expectedGuest));
        assertThat(resultsOf(guestId), equalTo(guestResults));
        assertThat(resultsOf(accountId), equalTo(List.of()));
    }

    @Test
    void shouldFinishWithoutSecondCall_whenFinish_givenNoSessionAndThenGuestCreated() {
        // given
        String email = TestPlayers.uniqueEmail("google-no-guest");
        String secret = GoogleSignIns.provenAtGoogle(email);
        int callsAfterReturn = callsToGoogle();
        ExtractableResponse<Response> refused = GoogleSignIns.finish(secret, null).extract();
        String nick = TestPlayers.uniqueNick("Later");
        String guestToken = TestPlayers.createGuest(nick, Rules.KIND_HUMAN);

        // when
        ValidatableResponse finished = GoogleSignIns.finish(secret, guestToken);

        // then
        assertThat(refused.statusCode(), equalTo(401));
        assertThat(refused.body().asString(), equalTo("{\"error\":\"no_session\"}"));
        assertThat(refused.headers().getValues("Set-Cookie"), equalTo(List.of()));
        finished.statusCode(200).body("", equalTo(Map.of("nick", nick, "kind", Rules.KIND_HUMAN, "account", true,
                "email", email, "merged", false)));
        assertThat(callsToGoogle(), equalTo(callsAfterReturn));
    }

    @Test
    void shouldFinishAfterRename_whenFinish_givenAccountHoldsGuestsNick() {
        // given
        String nick = TestPlayers.uniqueNick("Holder");
        accountByCode(nick, Rules.KIND_HUMAN, TestPlayers.uniqueEmail("google-holder"));
        String guestToken = TestPlayers.createGuest("Temp" + UUID.randomUUID().toString().substring(0, 8),
                Rules.KIND_HUMAN);
        insertNickDirectly(guestToken, nick);
        String email = TestPlayers.uniqueEmail("google-renamer");
        String secret = GoogleSignIns.provenAtGoogle(email);
        int callsAfterReturn = callsToGoogle();
        ExtractableResponse<Response> refused = GoogleSignIns.finish(secret, guestToken).extract();
        String newNick = TestPlayers.uniqueNick("Renamed");
        given().cookie(TestPlayers.COOKIE, guestToken).contentType(ContentType.JSON).body(Map.of("nick", newNick))
                .when().patch("/players/me").then().statusCode(200);

        // when
        ValidatableResponse finished = GoogleSignIns.finish(secret, guestToken);

        // then
        assertThat(refused.statusCode(), equalTo(409));
        assertThat(refused.body().asString(), equalTo("{\"error\":\"nick_taken\"}"));
        assertThat(refused.headers().getValues("Set-Cookie"), equalTo(List.of()));
        finished.statusCode(200).body("nick", equalTo(newNick)).body("email", equalTo(email));
        assertThat(callsToGoogle(), equalTo(callsAfterReturn));
    }

    @Test
    void shouldRefuseNoSession_whenFinish_givenNewAddressAndAccountOnDevice() {
        // given
        String accountToken = accountByCode(TestPlayers.uniqueNick("Owner"), Rules.KIND_HUMAN,
                TestPlayers.uniqueEmail("google-owner"));
        String secret = GoogleSignIns.provenAtGoogle(TestPlayers.uniqueEmail("google-second-address"));

        // when
        ValidatableResponse refused = GoogleSignIns.finish(secret, accountToken);

        // then
        refused.statusCode(401).body("", equalTo(Map.of("error", ApiException.NO_SESSION)));
        given().cookie(TestPlayers.COOKIE, accountToken).when().get("/players/me").then().statusCode(200);
    }

    // Both wait for the lock that the test holds, then run one after the
    // other: the first turns its guest into the account, the second logs
    // into it. Without the lock the second fails on the unique email.
    @Test
    void shouldLogBothIntoOneAccount_whenFinish_givenTwoDevicesFinishSameNewAddressAtOnce() throws Exception {
        // given
        String email = TestPlayers.uniqueEmail("google-together");
        String firstGuest = TestPlayers.createGuest(TestPlayers.uniqueNick("First"), Rules.KIND_HUMAN);
        String secondGuest = TestPlayers.createGuest(TestPlayers.uniqueNick("Second"), Rules.KIND_HUMAN);
        String firstSecret = GoogleSignIns.provenAtGoogle(email);
        String secondSecret = GoogleSignIns.provenAtGoogle(email);
        List<ExtractableResponse<Response>> responses = new ArrayList<>();
        long waiters;
        try (Connection holder = dataSource.getConnection();
             ExecutorService executor = Executors.newFixedThreadPool(2)) {
            holder.setAutoCommit(false);
            TestSql.execute(holder, "select pg_advisory_xact_lock(hashtextextended(?, 0))", email);
            Future<ExtractableResponse<Response>> first =
                    executor.submit(() -> GoogleSignIns.finish(firstSecret, firstGuest).extract());
            Future<ExtractableResponse<Response>> second =
                    executor.submit(() -> GoogleSignIns.finish(secondSecret, secondGuest).extract());
            waiters = TestSql.awaitLockWaiters(dataSource, 2);

            // when
            holder.commit();
            responses.add(first.get());
            responses.add(second.get());
        }

        // then
        assertThat(waiters, equalTo(2L));
        assertThat(responses.stream().map(ExtractableResponse::statusCode).toList(), equalTo(List.of(200, 200)));
        assertThat(responses.stream().map(response -> response.<Boolean>path("merged")).sorted().toList(),
                equalTo(List.of(false, true)));
        UUID accountId = playerId(GoogleSignIns.sessionTokenIn(responses.getFirst()));
        assertThat(playerId(GoogleSignIns.sessionTokenIn(responses.getLast())), equalTo(accountId));
        assertThat(TestSql.count(dataSource, "select count(*) from player where email = ?", email), equalTo(1L));
    }

    @Test
    void shouldRefuseSecondFinish_whenFinish_givenSignInFinished() {
        // given
        String guestToken = TestPlayers.createGuest(TestPlayers.uniqueNick("Once"), Rules.KIND_HUMAN);
        String secret = GoogleSignIns.provenAtGoogle(TestPlayers.uniqueEmail("google-once"));
        String accountToken = GoogleSignIns.sessionTokenIn(GoogleSignIns.finish(secret, guestToken)
                .statusCode(200).extract());

        // when
        ValidatableResponse second = GoogleSignIns.finish(secret, accountToken);

        // then
        second.statusCode(400).body("", equalTo(Map.of("error", ApiException.NO_GOOGLE_LOGIN)));
    }

    @Test
    void shouldRefuse_whenFinish_givenNoCookieOrUnknownCookie() {
        // given
        GoogleSignIns.provenAtGoogle(TestPlayers.uniqueEmail("google-someone"));

        // when
        ValidatableResponse withoutCookie = GoogleSignIns.finish(null, null);
        ValidatableResponse unknownCookie = GoogleSignIns.finish(GoogleSignIns.started().secret(), null);

        // then
        withoutCookie.statusCode(400).body("", equalTo(Map.of("error", ApiException.NO_GOOGLE_LOGIN)));
        unknownCookie.statusCode(400).body("", equalTo(Map.of("error", ApiException.NO_GOOGLE_LOGIN)));
    }

    @Test
    void shouldIgnoreBody_whenFinish_givenBodyOfAnyType() {
        // given
        String guestToken = TestPlayers.createGuest(TestPlayers.uniqueNick("Body"), Rules.KIND_HUMAN);
        String secret = GoogleSignIns.provenAtGoogle(TestPlayers.uniqueEmail("google-body"));

        // when
        ValidatableResponse finished = given().cookie(GoogleSignIns.COOKIE, secret)
                .cookie(TestPlayers.COOKIE, guestToken)
                .contentType("text/plain").body("anything")
                .when().post("/login/google/finish").then();

        // then
        finished.statusCode(200).body("account", equalTo(true));
    }

    @Test
    void shouldStoreOnlyAddressInPlayer_whenFinish_givenGoogleSignIn() throws SQLException {
        // given
        String guestToken = TestPlayers.createGuest(TestPlayers.uniqueNick("Stored"), Rules.KIND_HUMAN);
        String email = TestPlayers.uniqueEmail("google-stored");
        GoogleSignIns.Started started = GoogleSignIns.started();
        String code = started.code(email);
        GoogleSignIns.returnFromGoogle(started, code).statusCode(302).header("Location", GoogleSignIns.SIGNED_IN);

        // when
        GoogleSignIns.finish(started.secret(), guestToken).statusCode(200);

        // then
        assertThat(TestSql.tablesHolding(dataSource, email), equalTo(List.of("player")));
        for (String fromGoogle : List.of(code, FakeGoogle.accessTokenFor(code), FakeGoogle.subjectFor(code),
                FakeGoogle.signatureFor(code), FakeGoogle.CLIENT_SECRET, started.secret(), started.state(),
                started.query().get("nonce"))) {
            assertThat(fromGoogle, TestSql.tablesHolding(dataSource, fromGoogle), equalTo(List.of()));
        }
    }

    @Test
    void shouldLogNothingFromGoogle_whenSignIn_givenSuccessAndFailures() {
        // given
        String email = TestPlayers.uniqueEmail("google-logged");
        String guestToken = TestPlayers.createGuest(TestPlayers.uniqueNick("Logged"), Rules.KIND_HUMAN);
        GoogleSignIns.Started signedIn = GoogleSignIns.started();
        GoogleSignIns.Started refused = GoogleSignIns.started();
        GoogleSignIns.Started unverified = GoogleSignIns.started();
        GoogleSignIns.Started notJson = GoogleSignIns.started();
        String signedInCode = signedIn.code(email);
        String refusedCode = refused.code(email, true, FakeGoogle.ANSWER_ERROR);
        String unverifiedCode = unverified.code(email, false, FakeGoogle.ANSWER_TOKEN);
        String notJsonCode = notJson.code(email, true, FakeGoogle.ANSWER_NOT_JSON);
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Handler collector = collector(records);
        Logger root = Logger.getLogger("");
        root.addHandler(collector);

        // when
        try {
            GoogleSignIns.returnFromGoogle(refused, refusedCode).header("Location", GoogleSignIns.FAILED);
            GoogleSignIns.returnFromGoogle(unverified, unverifiedCode).header("Location", GoogleSignIns.FAILED);
            GoogleSignIns.returnFromGoogle(notJson, notJsonCode).header("Location", GoogleSignIns.FAILED);
            GoogleSignIns.returnFromGoogle(signedIn, signedInCode).header("Location", GoogleSignIns.SIGNED_IN);
            GoogleSignIns.finish(signedIn.secret(), null).statusCode(401);
            GoogleSignIns.finish(signedIn.secret(), guestToken).statusCode(200);
        } finally {
            root.removeHandler(collector);
        }

        // then
        assertThat(googleMessages(records), equalTo(List.of(
                "Google token call failed: status 400",
                "Google ID token refused: email_verified",
                "Google token call failed: " + JsonParseException.class.getName())));
        assertThat(records.stream().filter(record -> null != record.getThrown()).toList(), empty());
        List<String> secrets = new ArrayList<>(List.of(email, FakeGoogle.CLIENT_SECRET, signedIn.secret(),
                refused.secret(), unverified.secret(), notJson.secret()));
        for (String code : List.of(signedInCode, refusedCode, unverifiedCode, notJsonCode)) {
            secrets.addAll(List.of(code, FakeGoogle.accessTokenFor(code), FakeGoogle.signatureFor(code),
                    FakeGoogle.subjectFor(code)));
        }
        for (String secret : secrets) {
            assertThat(secret, records.stream().filter(record -> LoginMailFailureTest.textOf(record).contains(secret))
                    .toList(), empty());
        }
    }

    @Test
    void shouldAnswerJsonError_whenFinish_givenGet() {
        // when
        ValidatableResponse response = given().when().get("/login/google/finish").then();

        // then
        response.statusCode(405).contentType(ContentType.JSON).body("error", equalTo("method_not_allowed"));
    }

    private int callsToGoogle() {
        String calls = given().when().get(tokenEndpoint.replace("/token", FakeGoogle.CALLS_PATH))
                .then().statusCode(200).extract().asString();
        return Integer.parseInt(calls);
    }

    private void awaitCallsToGoogle(int expected) throws InterruptedException {
        for (int attempt = 1; expected > callsToGoogle() && 100 > attempt; attempt++) {
            Thread.sleep(50);
        }
        assertThat(callsToGoogle(), equalTo(expected));
    }

    private String accountByCode(String nick, String kind, String email) {
        String guestToken = TestPlayers.createGuest(nick, kind);
        given().contentType(ContentType.JSON).body(Map.of("email", email))
                .when().post("/login/code").then().statusCode(202);
        Matcher code = MAILED_CODE.matcher(mailbox.getMailMessagesSentTo(email).getLast().getText());
        assertThat(code.find(), equalTo(true));
        return given().cookie(TestPlayers.COOKIE, guestToken).contentType(ContentType.JSON)
                .body(Map.of("email", email, "code", code.group()))
                .when().post("/login/verify").then().statusCode(200).extract().cookie(TestPlayers.COOKIE);
    }

    // A guest that took the nick before the account did keeps it: guests are
    // not checked against accounts written later.
    private void insertNickDirectly(String guestToken, String nick) {
        try {
            TestSql.execute(dataSource, "update player set nick = ?, nick_key = ? where id = ?", nick, Nicks.key(nick),
                    playerId(guestToken));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID playerId(String token) {
        try {
            return (UUID) TestSql.row(dataSource, "select player_id from session where token_hash = ?",
                    Sessions.hash(token)).getFirst();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void submitRun(String token) {
        given().cookie(TestPlayers.COOKIE, token).contentType(ContentType.JSON)
                .body(Map.of("levelsCleared", 1, "score", 40, "ticks", 900))
                .when().post("/runs").then().statusCode(201);
    }

    private static void submitClear(String token) {
        given().cookie(TestPlayers.COOKIE, token).contentType(ContentType.JSON)
                .body(Map.of("level", 2, "shape", Rules.SHAPE_PORTRAIT, "revision", Rules.currentRevision(2),
                        "ticks", 900, "chests", 1, "chestsTotal", 5))
                .when().post("/clears").then().statusCode(201);
    }

    // The player id comes second, so a moved row differs only there.
    private List<List<Object>> resultsOf(UUID playerId) throws SQLException {
        List<List<Object>> results = new ArrayList<>(TestSql.rows(dataSource, """
                select id, player_id, levels_cleared, score, ticks_total, finished_at
                from game_run where player_id = ? order by id
                """, playerId));
        results.addAll(TestSql.rows(dataSource, """
                select id, player_id, level, shape, revision, ticks, chests, chests_total, created_at
                from level_clear where player_id = ? order by id
                """, playerId));
        return results;
    }

    private static List<List<Object>> movedTo(UUID playerId, List<List<Object>> rows) {
        List<List<Object>> moved = new ArrayList<>();
        for (List<Object> row : rows) {
            List<Object> movedRow = new ArrayList<>(row);
            movedRow.set(1, playerId);
            moved.add(movedRow);
        }
        return moved;
    }

    private static List<String> googleMessages(List<LogRecord> records) {
        return records.stream()
                .filter(record -> record.getLoggerName().startsWith(GoogleLogins.class.getPackageName() + ".Google"))
                .map(LogRecord::getMessage)
                .toList();
    }

    private static Handler collector(List<LogRecord> records) {
        return new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
    }

    private static void assertSecurityHeaders(ExtractableResponse<Response> response) {
        assertThat(response.header("Cache-Control"), equalTo("no-store"));
        assertThat(response.header("X-Content-Type-Options"), equalTo("nosniff"));
        assertThat(response.header("Content-Security-Policy"), equalTo(CONTENT_SECURITY_POLICY));
    }

    private static Arguments badReturn(String name,
                                       BiFunction<GoogleSignIns.Started, String, ValidatableResponse> sendReturn) {
        return Arguments.of(Named.of(name, sendReturn));
    }
}
