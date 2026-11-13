package dev.rassimok.api.klad;

import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import io.vertx.ext.mail.MailMessage;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class LoginResourceTest {

    private static final String FROM = "КЛАД <klad@mail.rassimok.dev>";
    private static final Pattern CODE = Pattern.compile("(?<![0-9])[0-9]{6}(?![0-9])");
    private static final String COOKIE_PATTERN = "klad_session=([A-Za-z0-9_-]{43}); Path=/klad/api; HttpOnly; "
            + "Secure; SameSite=Strict; Max-Age=7776000";
    private static final String CLEARED_COOKIE = "klad_session=; Path=/klad/api; HttpOnly; Secure; "
            + "SameSite=Strict; Max-Age=0";
    private static final String LOGIN_CODE_ROW = """
            select email_hash, code_hash, attempts, expires_at, created_at from login_code where email_hash = ?
            """;
    private static final String PLAYER_ROW = """
            select id, nick, nick_key, kind, email, created_at, last_seen_at from player where id = ?
            """;

    @Inject
    DataSource dataSource;

    @Inject
    MockMailbox mailbox;

    // The send log is shared by every test class, and the daily limit counts
    // all of it.
    @BeforeEach
    @AfterEach
    void clearSendLog() throws SQLException {
        TestSql.execute(dataSource, "delete from login_mail");
    }

    @Test
    void shouldSendCodeAndStoreOnlyHashes_whenRequestCode() throws SQLException {
        // given
        String email = TestPlayers.uniqueEmail("sent");

        // when
        ValidatableResponse response = given().contentType(ContentType.JSON)
                .body(Map.of("email", "  " + email.toUpperCase(Locale.ROOT) + " "))
                .when().post("/login/code")
                .then();

        // then
        response.statusCode(202).body("", equalTo(Map.of()));
        List<MailMessage> mails = mailbox.getMailMessagesSentTo(email);
        assertThat(mails.size(), equalTo(1));
        MailMessage mail = mails.getFirst();
        assertThat(mail.getFrom(), equalTo(FROM));
        assertThat(mail.getSubject(), equalTo(LoginCodes.SUBJECT));
        assertThat(mail.getHtml(), nullValue());
        String code = codeIn(mail.getText());
        List<Object> stored = TestSql.row(dataSource, """
                select code_hash, attempts, expires_at - created_at = interval '10 minutes',
                       now() - created_at < interval '1 minute'
                from login_code where email_hash = ?
                """, Sessions.hash(email));
        assertThat(stored, equalTo(List.of(LoginCodes.hash(email, code), 0, true, true)));
        assertThat(TestSql.count(dataSource, "select count(*) from login_mail where email_hash = ?",
                Sessions.hash(email)), equalTo(1L));
        assertThat(TestSql.count(dataSource, "select count(*) from login_mail"), equalTo(1L));
    }

    @Test
    void shouldStoreAddressInNoTable_whenRequestCode() throws SQLException {
        // given
        String email = TestPlayers.uniqueEmail("hidden");

        // when
        given().contentType(ContentType.JSON).body(Map.of("email", " " + email.toUpperCase(Locale.ROOT)))
                .when().post("/login/code")
                .then().statusCode(202);

        // then
        assertThat(codeRowsFor(email), equalTo(1L));
        assertThat(tablesHolding(email), equalTo(List.of()));
    }

    @Test
    void shouldStoreAddressOnlyInPlayer_whenVerify_givenGuestBecomesAccount() throws SQLException {
        // given
        String guestToken = TestPlayers.createGuest("AddressKeeper", Rules.KIND_HUMAN);
        String email = TestPlayers.uniqueEmail("kept");
        String code = requestCode(email);

        // when
        verify(guestToken, email, code).statusCode(200);

        // then
        assertThat(tablesHolding(email), equalTo(List.of("player")));
    }

    @Test
    void shouldAnswerTheSame_whenRequestCode_givenEmailWithAndWithoutAccount() {
        // given
        String accountEmail = TestPlayers.uniqueEmail("known");
        createAccount("Known", accountEmail);
        String newEmail = TestPlayers.uniqueEmail("unknown");

        // when
        ExtractableResponse<Response> known = postCode(accountEmail).extract();
        ExtractableResponse<Response> unknown = postCode(newEmail).extract();

        // then
        assertThat(known.statusCode(), equalTo(202));
        assertThat(unknown.statusCode(), equalTo(202));
        assertThat(known.body().asString(), equalTo(unknown.body().asString()));
        assertThat(mailbox.getMailMessagesSentTo(accountEmail).size(), equalTo(2));
        assertThat(mailbox.getMailMessagesSentTo(newEmail).size(), equalTo(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"email\":\"player\"}", "{\"email\":\"a@b@example.com\"}", "{\"email\":12}", "{}",
            "{\"email\":\"a@example.com\\r\\nBcc: b@example.com\"}", "{\"email\":\"x <a@example.com>\"}",
            "{\"email\":\"a@example.com,b@example.com\"}", "[]"})
    void shouldRejectAndSendNothing_whenRequestCode_givenInvalidEmail(String body) throws SQLException {
        // given
        int sentBefore = mailbox.getTotalMessagesSent();

        // when
        ValidatableResponse response = given().contentType(ContentType.JSON).body(body)
                .when().post("/login/code").then();

        // then
        response.statusCode(400).body("error", equalTo(ApiException.INVALID_EMAIL));
        assertThat(mailbox.getTotalMessagesSent(), equalTo(sentBefore));
        assertThat(TestSql.count(dataSource, "select count(*) from login_mail"), equalTo(0L));
    }

    @Test
    void shouldReject_whenRequestCode_givenEmailOver254Characters() {
        // given
        String email = "a".repeat(64) + "@" + "b".repeat(190);

        // when
        ValidatableResponse response = postCode(email);

        // then
        response.statusCode(400).body("error", equalTo(ApiException.INVALID_EMAIL));
    }

    @Test
    void shouldReplacePreviousCode_whenRequestCode_givenCodeAlreadySent() throws SQLException {
        // given
        String email = TestPlayers.uniqueEmail("again");
        verify(null, email, otherCode(requestCode(email))).statusCode(400);
        assertThat(TestSql.row(dataSource, LOGIN_CODE_ROW, Sessions.hash(email)).get(2), equalTo(1));

        // when
        postCode(email).statusCode(202);

        // then
        String secondCode = sentCode(email);
        assertThat(codeRowsFor(email), equalTo(1L));
        List<Object> stored = TestSql.row(dataSource,
                "select code_hash, attempts from login_code where email_hash = ?", Sessions.hash(email));
        assertThat(stored, equalTo(List.of(LoginCodes.hash(email, secondCode), 0)));
    }

    @Test
    void shouldDeleteExpiredCodesOfOtherEmails_whenRequestCode() throws SQLException {
        // given
        String expiredEmail = TestPlayers.uniqueEmail("stale");
        seedExpiredCode(expiredEmail);

        // when
        postCode(TestPlayers.uniqueEmail("sweeper")).statusCode(202);

        // then
        assertThat(codeRowsFor(expiredEmail), equalTo(0L));
    }

    @Test
    void shouldStillDeleteOldRows_whenRequestCode_givenRefusedRequest() throws SQLException {
        // given
        String email = TestPlayers.uniqueEmail("refused");
        for (int i = 0; i < Rules.CODES_PER_EMAIL; i++) {
            postCode(email).statusCode(202);
        }
        seedSentMails(1, "25 hours");
        String expiredEmail = TestPlayers.uniqueEmail("stale-again");
        seedExpiredCode(expiredEmail);

        // when
        ValidatableResponse response = postCode(email);

        // then
        response.statusCode(429).body("error", equalTo(ApiException.RATE_LIMITED));
        assertThat(TestSql.count(dataSource, "select count(*) from login_mail"),
                equalTo((long) Rules.CODES_PER_EMAIL));
        assertThat(codeRowsFor(expiredEmail), equalTo(0L));
        assertThat(codeRowsFor(email), equalTo(1L));
    }

    @Test
    void shouldRefuseFourth_whenRequestCode_givenThreeCodesToEmailThisHour() throws SQLException {
        // given
        String email = TestPlayers.uniqueEmail("busy");
        for (int i = 0; i < 3; i++) {
            postCode(email).statusCode(202);
        }

        // when
        ValidatableResponse response = postCode(email);

        // then
        response.statusCode(429).body("error", equalTo(ApiException.RATE_LIMITED));
        assertThat(mailbox.getMailMessagesSentTo(email).size(), equalTo(3));
        assertThat(TestSql.count(dataSource, "select count(*) from login_mail where email_hash = ?",
                Sessions.hash(email)), equalTo(3L));
    }

    @Test
    void shouldAccept_whenRequestCode_givenThreeCodesToEmailOlderThanAnHour() throws SQLException {
        // given
        String email = TestPlayers.uniqueEmail("patient");
        for (int i = 0; i < 3; i++) {
            postCode(email).statusCode(202);
        }
        TestSql.execute(dataSource, "update login_mail set sent_at = sent_at - interval '61 minutes'");

        // when
        ValidatableResponse response = postCode(email);

        // then
        response.statusCode(202);
        assertThat(mailbox.getMailMessagesSentTo(email).size(), equalTo(4));
    }

    @Test
    void shouldCountEachEmailApart_whenRequestCode_givenAnotherEmailAtTheLimit() {
        // given
        String busy = TestPlayers.uniqueEmail("full");
        for (int i = 0; i < 3; i++) {
            postCode(busy).statusCode(202);
        }

        // when
        ValidatableResponse response = postCode(TestPlayers.uniqueEmail("free"));

        // then
        response.statusCode(202);
    }

    @Test
    void shouldRefuseEightyFirst_whenRequestCode_givenSeventyNineMailsToday() throws SQLException {
        // given
        seedSentMails(Rules.MAILS_PER_DAY - 1, "23 hours");
        postCode(TestPlayers.uniqueEmail("eightieth")).statusCode(202);
        String email = TestPlayers.uniqueEmail("eighty-first");

        // when
        ValidatableResponse response = postCode(email);

        // then
        response.statusCode(429).body("error", equalTo(ApiException.RATE_LIMITED));
        assertThat(mailbox.getMailMessagesSentTo(email).size(), equalTo(0));
        assertThat(TestSql.count(dataSource, "select count(*) from login_mail"), equalTo((long) Rules.MAILS_PER_DAY));
    }

    @Test
    void shouldDeleteOldMailsAndAccept_whenRequestCode_givenEightyMailsOlderThanADay() throws SQLException {
        // given
        seedSentMails(Rules.MAILS_PER_DAY, "25 hours");

        // when
        ValidatableResponse response = postCode(TestPlayers.uniqueEmail("next-day"));

        // then
        response.statusCode(202);
        assertThat(TestSql.count(dataSource, "select count(*) from login_mail"), equalTo(1L));
    }

    // Two codes first, so the one left to send does not depend on how many of
    // the burst get one of the instance's request slots.
    @Test
    void shouldSendOnlyThree_whenRequestCode_givenTenParallelRequestsForOneEmail()
            throws InterruptedException, SQLException {
        // given
        String email = TestPlayers.uniqueEmail("burst");
        postCode(email).statusCode(202);
        postCode(email).statusCode(202);
        List<Callable<Integer>> requests = Collections.nCopies(10, () -> postCode(email).extract().statusCode());

        // when
        Map<Integer, Long> statusCounts = inParallel(requests);

        // then
        assertThat(statusCounts, equalTo(Map.of(202, 1L, 429, 9L)));
        assertThat(mailbox.getMailMessagesSentTo(email).size(), equalTo(3));
        assertThat(TestSql.count(dataSource, "select count(*) from login_mail"), equalTo(3L));
    }

    @Test
    void shouldRefuseAndRecover_whenRequestCode_givenCodeRequestsLockedPastTimeout() throws SQLException {
        // given
        String email = TestPlayers.uniqueEmail("queued");

        // when
        ValidatableResponse response;
        try (Connection otherRequest = dataSource.getConnection()) {
            otherRequest.setAutoCommit(false);
            TestSql.execute(otherRequest, "lock table login_mail in exclusive mode");
            response = postCode(email);
            otherRequest.rollback();
        }

        // then
        response.statusCode(429).body("error", equalTo(ApiException.RATE_LIMITED));
        assertThat(mailbox.getMailMessagesSentTo(email).size(), equalTo(0));
        postCode(email).statusCode(202);
    }

    // The test's connection does what a verify does: lock the code row, then
    // delete it (a right code or the fifth wrong one) while the request waits.
    @Test
    void shouldStoreNewCode_whenRequestCode_givenVerifyDeletesCodeMeanwhile() throws Exception {
        // given
        String email = TestPlayers.uniqueEmail("overtaken");
        requestCode(email);
        String emailHash = Sessions.hash(email);

        // when
        long lockWaiters;
        int status;
        try (ExecutorService executor = Executors.newSingleThreadExecutor();
             Connection verifier = dataSource.getConnection()) {
            verifier.setAutoCommit(false);
            TestSql.execute(verifier, "select 1 from login_code where email_hash = ? for update", emailHash);
            Future<Integer> request = executor.submit(() -> postCode(email).extract().statusCode());
            lockWaiters = TestSql.awaitLockWaiters(dataSource, 1);
            TestSql.execute(verifier, "delete from login_code where email_hash = ?", emailHash);
            verifier.commit();
            status = request.get();
        }

        // then
        assertThat(lockWaiters, equalTo(1L));
        assertThat(status, equalTo(202));
        List<Object> stored = TestSql.row(dataSource, """
                select email_hash, code_hash, attempts, expires_at - created_at = interval '10 minutes',
                       now() - created_at < interval '1 minute'
                from login_code where email_hash = ?
                """, emailHash);
        assertThat(stored, equalTo(List.of(emailHash, LoginCodes.hash(email, sentCode(email)), 0, true, true)));
    }

    @Test
    void shouldRefuseAndRecover_whenRequestCode_givenCodeRowLockedPastTimeout() throws SQLException {
        // given
        String email = TestPlayers.uniqueEmail("row-held");
        requestCode(email);
        String emailHash = Sessions.hash(email);
        List<Object> expected = TestSql.row(dataSource, LOGIN_CODE_ROW, emailHash);

        // when
        ValidatableResponse response;
        try (Connection verifier = dataSource.getConnection()) {
            verifier.setAutoCommit(false);
            TestSql.execute(verifier, "select 1 from login_code where email_hash = ? for update", emailHash);
            response = postCode(email);
            verifier.rollback();
        }

        // then
        response.statusCode(429).body("error", equalTo(ApiException.RATE_LIMITED));
        assertThat(mailbox.getMailMessagesSentTo(email).size(), equalTo(1));
        assertThat(TestSql.row(dataSource, LOGIN_CODE_ROW, emailHash), equalTo(expected));
        postCode(email).statusCode(202);
    }

    // Two requests wait for the table lock and hold both of the instance's
    // request slots, so a third is refused without waiting.
    @Test
    void shouldRefuseAtOnce_whenRequestCode_givenTwoRequestsWaitingForLock() throws Exception {
        // given
        String email = TestPlayers.uniqueEmail("third-in-line");

        // when
        long waitersBefore;
        ValidatableResponse third;
        long waitersAfter;
        List<Integer> waitingStatuses;
        try (ExecutorService executor = Executors.newFixedThreadPool(2);
             Connection otherInstance = dataSource.getConnection()) {
            otherInstance.setAutoCommit(false);
            TestSql.execute(otherInstance, "lock table login_mail in exclusive mode");
            Future<Integer> first = executor.submit(
                    () -> postCode(TestPlayers.uniqueEmail("first-in-line")).extract().statusCode());
            Future<Integer> second = executor.submit(
                    () -> postCode(TestPlayers.uniqueEmail("second-in-line")).extract().statusCode());
            waitersBefore = TestSql.awaitLockWaiters(dataSource, 2);
            third = postCode(email);
            waitersAfter = TestSql.lockWaiters(dataSource);
            otherInstance.rollback();
            waitingStatuses = List.of(first.get(), second.get());
        }

        // then
        assertThat(waitersBefore, equalTo(2L));
        third.statusCode(429).body("error", equalTo(ApiException.RATE_LIMITED));
        assertThat(waitersAfter, equalTo(2L));
        assertThat(waitingStatuses, equalTo(List.of(202, 202)));
        assertThat(mailbox.getMailMessagesSentTo(email).size(), equalTo(0));
        postCode(email).statusCode(202);
    }

    @Test
    void shouldTurnGuestIntoAccountWithNewCookie_whenVerify_givenGuestOnDevice() throws SQLException {
        // given
        String guestToken = TestPlayers.createGuest("Becomer", Rules.KIND_AGENT);
        String email = TestPlayers.uniqueEmail("becomer");
        String player = """
                select player.id, player.nick, player.nick_key, player.kind, player.email, player.created_at,
                       now() - player.last_seen_at < interval '1 minute'
                from player where id = ?
                """;
        UUID playerId = playerId(guestToken);
        List<Object> expected = TestSql.row(dataSource, player, playerId);
        expected.set(4, email);
        String code = requestCode(email);
        int sentBefore = mailbox.getTotalMessagesSent();

        // when
        ExtractableResponse<Response> response = verify(guestToken, email, code).extract();

        // then
        assertThat(response.statusCode(), equalTo(200));
        assertThat(mailbox.getTotalMessagesSent(), equalTo(sentBefore));
        assertThat(response.as(LoginView.class), equalTo(new LoginView("Becomer", Rules.KIND_AGENT, true, email, false)));
        String newToken = tokenIn(response);
        assertThat(newToken, not(equalTo(guestToken)));
        assertThat(TestSql.row(dataSource, player, playerId), equalTo(expected));
        assertThat(playerId(newToken), equalTo(playerId));
        given().cookie(TestPlayers.COOKIE, guestToken).when().get("/players/me")
                .then().statusCode(401).body("error", equalTo(ApiException.NO_SESSION));
        assertThat(codeRowsFor(email), equalTo(0L));
    }

    @Test
    void shouldReadOwnEmail_whenMe_givenAccount() {
        // given
        String email = TestPlayers.uniqueEmail("reader");
        String token = createAccount("EmailReader", email);

        // when
        ExtractableResponse<Response> response = given().cookie(TestPlayers.COOKIE, token)
                .when().get("/players/me").then().extract();

        // then
        assertThat(response.statusCode(), equalTo(200));
        assertThat(response.as(PlayerView.class), equalTo(new PlayerView("EmailReader", Rules.KIND_HUMAN, true, email)));
    }

    @Test
    void shouldLogSecondDeviceIntoAccountWithNewCookie_whenVerify_givenNoSession() throws SQLException {
        // given
        String email = TestPlayers.uniqueEmail("traveller");
        String firstDevice = createAccount("Traveller", email);
        String code = requestCode(email);

        // when
        ExtractableResponse<Response> response = verify(null, email, code).extract();

        // then
        assertThat(response.statusCode(), equalTo(200));
        assertThat(response.as(LoginView.class), equalTo(new LoginView("Traveller", Rules.KIND_HUMAN, true, email, false)));
        String secondDevice = tokenIn(response);
        assertThat(secondDevice, not(equalTo(firstDevice)));
        assertThat(playerId(secondDevice), equalTo(playerId(firstDevice)));
        given().cookie(TestPlayers.COOKIE, firstDevice).when().get("/players/me")
                .then().statusCode(200).body("email", equalTo(email));
        assertThat(TestSql.count(dataSource, "select count(*) from player where email = ?", email), equalTo(1L));
    }

    @Test
    void shouldLogIntoAccountAndLeaveGuest_whenVerify_givenGuestOfOtherKindOnDevice() throws SQLException {
        // given
        String email = TestPlayers.uniqueEmail("owner");
        String accountToken = createAccount("Owner", email);
        UUID accountId = playerId(accountToken);
        String guestToken = TestPlayers.createGuest("Visitor", Rules.KIND_AGENT);
        UUID guestId = playerId(guestToken);
        submitClear(guestToken, 2, 900, 1);
        submitRun(guestToken, 1, 40, 900);
        List<Object> expectedGuest = TestSql.row(dataSource, PLAYER_ROW, guestId);
        List<List<Object>> expectedClears = clearsOf(guestId);
        List<List<Object>> expectedRuns = runsOf(guestId);
        String code = requestCode(email);

        // when
        ExtractableResponse<Response> response = verify(guestToken, email, code).extract();

        // then
        assertThat(response.statusCode(), equalTo(200));
        assertThat(response.as(LoginView.class), equalTo(new LoginView("Owner", Rules.KIND_HUMAN, true, email, false)));
        assertThat(playerId(tokenIn(response)), equalTo(accountId));
        assertThat(TestSql.row(dataSource, PLAYER_ROW, guestId), equalTo(expectedGuest));
        assertThat(clearsOf(guestId), equalTo(expectedClears));
        assertThat(runsOf(guestId), equalTo(expectedRuns));
        assertThat(clearsOf(accountId), equalTo(List.of()));
        assertThat(runsOf(accountId), equalTo(List.of()));
        given().cookie(TestPlayers.COOKIE, guestToken).when().get("/players/me").then().statusCode(401);
    }

    @Test
    void shouldMoveResultsAndDeleteGuest_whenVerify_givenGuestOfSameKindOnDevice() throws SQLException {
        // given
        String email = TestPlayers.uniqueEmail("merge-target");
        String accountToken = createAccount("MergeTarget", Rules.KIND_AGENT, email);
        UUID accountId = playerId(accountToken);
        submitRun(accountToken, 1, 10, 900);
        String guestToken = TestPlayers.createGuest("MergedGuest", Rules.KIND_AGENT);
        UUID guestId = playerId(guestToken);
        submitClear(guestToken, 4, 1200, 3);
        submitRun(guestToken, Rules.LEVEL_MAX, Rules.RUN_SCORE_MAX, Rules.LEVEL_MAX * Rules.TICKS_FLOOR);
        String accountRow = """
                select id, nick, nick_key, kind, email, created_at, now() - last_seen_at < interval '1 minute'
                from player where id = ?
                """;
        List<Object> expectedAccount = TestSql.row(dataSource, accountRow, accountId);
        List<List<Object>> expectedClears = movedTo(accountId, clearsOf(guestId));
        List<List<Object>> expectedRuns = new ArrayList<>(runsOf(accountId));
        expectedRuns.addAll(movedTo(accountId, runsOf(guestId)));
        String code = requestCode(email);

        // when
        ExtractableResponse<Response> response = verify(guestToken, email, code).extract();

        // then
        assertThat(response.statusCode(), equalTo(200));
        assertThat(response.as(LoginView.class),
                equalTo(new LoginView("MergeTarget", Rules.KIND_AGENT, true, email, true)));
        assertThat(playerId(tokenIn(response)), equalTo(accountId));
        assertThat(TestSql.row(dataSource, accountRow, accountId), equalTo(expectedAccount));
        assertThat(clearsOf(accountId), equalTo(expectedClears));
        assertThat(runsOf(accountId), equalTo(expectedRuns));
        assertThat(rowsOf(guestId), equalTo(List.of(0L, 0L, 0L, 0L)));
        given().queryParam("kind", Rules.KIND_AGENT).when().get("/boards/records").then()
                .body("entries.findAll { it.nick == 'MergeTarget' }.levelsCleared", equalTo(List.of(Rules.LEVEL_MAX)))
                .body("entries.nick", not(hasItem("MergedGuest")));
    }

    @Test
    void shouldMergeNothing_whenVerify_givenSameAccountAlreadyOnDevice() throws SQLException {
        // given
        String email = TestPlayers.uniqueEmail("already-in");
        String accountToken = createAccount("AlreadyIn", email);
        UUID accountId = playerId(accountToken);
        submitRun(accountToken, 1, 10, 900);
        List<List<Object>> expectedRuns = runsOf(accountId);
        String code = requestCode(email);

        // when
        ExtractableResponse<Response> response = verify(accountToken, email, code).extract();

        // then
        assertThat(response.statusCode(), equalTo(200));
        assertThat(response.as(LoginView.class),
                equalTo(new LoginView("AlreadyIn", Rules.KIND_HUMAN, true, email, false)));
        assertThat(playerId(tokenIn(response)), equalTo(accountId));
        assertThat(runsOf(accountId), equalTo(expectedRuns));
    }

    @Test
    void shouldCountAttempt_whenVerify_givenWrongCode() throws SQLException {
        // given
        String email = TestPlayers.uniqueEmail("typo");
        String code = requestCode(email);
        List<Object> expected = TestSql.row(dataSource, LOGIN_CODE_ROW, Sessions.hash(email));
        expected.set(2, 1);

        // when
        ValidatableResponse response = verify(null, email, otherCode(code));

        // then
        response.statusCode(400).body("error", equalTo(ApiException.INVALID_CODE));
        assertThat(TestSql.row(dataSource, LOGIN_CODE_ROW, Sessions.hash(email)), equalTo(expected));
    }

    @Test
    void shouldAnswerTheSame_whenVerify_givenWrongCodeForEmailWithAndWithoutAccount() {
        // given
        String accountEmail = TestPlayers.uniqueEmail("wrong-known");
        createAccount("WrongKnown", accountEmail);
        String accountCode = requestCode(accountEmail);
        String newEmail = TestPlayers.uniqueEmail("wrong-unknown");
        String newCode = requestCode(newEmail);
        String guestToken = TestPlayers.createGuest("WrongGuesser", Rules.KIND_HUMAN);

        // when
        ExtractableResponse<Response> known = verify(guestToken, accountEmail, otherCode(accountCode)).extract();
        ExtractableResponse<Response> unknown = verify(guestToken, newEmail, otherCode(newCode)).extract();

        // then
        assertThat(known.statusCode(), equalTo(400));
        assertThat(unknown.statusCode(), equalTo(400));
        assertThat(known.body().asString(), equalTo("{\"error\":\"invalid_code\"}"));
        assertThat(unknown.body().asString(), equalTo(known.body().asString()));
        assertThat(known.header("Set-Cookie"), nullValue());
        assertThat(unknown.header("Set-Cookie"), nullValue());
    }

    @Test
    void shouldAcceptRightCode_whenVerify_givenFourWrongAttempts() {
        // given
        String email = TestPlayers.uniqueEmail("careful");
        String guestToken = TestPlayers.createGuest("Careful", Rules.KIND_HUMAN);
        String code = requestCode(email);
        for (int i = 0; i < Rules.CODE_ATTEMPTS - 1; i++) {
            verify(guestToken, email, otherCode(code)).statusCode(400).body("error", equalTo(ApiException.INVALID_CODE));
        }

        // when
        ValidatableResponse response = verify(guestToken, email, code);

        // then
        response.statusCode(200).body("account", equalTo(true));
    }

    @Test
    void shouldDeleteCode_whenVerify_givenFifthWrongAttempt() throws SQLException {
        // given
        String email = TestPlayers.uniqueEmail("guesser");
        String guestToken = TestPlayers.createGuest("Guesser", Rules.KIND_HUMAN);
        String code = requestCode(email);
        for (int i = 0; i < Rules.CODE_ATTEMPTS - 1; i++) {
            verify(guestToken, email, otherCode(code)).statusCode(400);
        }

        // when
        ValidatableResponse fifth = verify(guestToken, email, otherCode(code));

        // then
        fifth.statusCode(429).body("error", equalTo(ApiException.TOO_MANY_ATTEMPTS));
        assertThat(codeRowsFor(email), equalTo(0L));
        verify(guestToken, email, code).statusCode(400).body("error", equalTo(ApiException.INVALID_CODE));
    }

    @Test
    void shouldAllowOnlyFiveAttempts_whenVerify_givenTenParallelWrongCodes() throws InterruptedException, SQLException {
        // given
        String email = TestPlayers.uniqueEmail("parallel");
        String wrongCode = otherCode(requestCode(email));
        List<Callable<Integer>> guesses = Collections.nCopies(10,
                () -> verify(null, email, wrongCode).extract().statusCode());

        // when
        Map<Integer, Long> statusCounts = inParallel(guesses);

        // then
        assertThat(statusCounts, equalTo(Map.of(400, 9L, 429, 1L)));
        assertThat(codeRowsFor(email), equalTo(0L));
    }

    @Test
    void shouldRefuseAndDeleteCode_whenVerify_givenExpiredCode() throws SQLException {
        // given
        String email = TestPlayers.uniqueEmail("late");
        String guestToken = TestPlayers.createGuest("Late", Rules.KIND_HUMAN);
        String code = requestCode(email);
        TestSql.execute(dataSource,
                "update login_code set expires_at = now() - interval '1 second' where email_hash = ?",
                Sessions.hash(email));

        // when
        ValidatableResponse response = verify(guestToken, email, code);

        // then
        response.statusCode(400).body("error", equalTo(ApiException.INVALID_CODE));
        assertThat(codeRowsFor(email), equalTo(0L));
        given().cookie(TestPlayers.COOKIE, guestToken).when().get("/players/me").then().body("account", equalTo(false));
    }

    @Test
    void shouldRefuseUsedCode_whenVerify_givenCodeAlreadyUsed() {
        // given
        String email = TestPlayers.uniqueEmail("twice");
        String guestToken = TestPlayers.createGuest("Twice", Rules.KIND_HUMAN);
        String code = requestCode(email);
        verify(guestToken, email, code).statusCode(200);

        // when
        ValidatableResponse response = verify(null, email, code);

        // then
        response.statusCode(400).body("error", equalTo(ApiException.INVALID_CODE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"email\":\"nobody@example.com\",\"code\":\"123456\"}",
            "{\"email\":\"nobody\",\"code\":\"123456\"}", "{\"code\":\"123456\"}", "{}", "[]"})
    void shouldRefuse_whenVerify_givenNoCodeForEmail(String body) {
        // when
        ValidatableResponse response = given().contentType(ContentType.JSON).body(body)
                .when().post("/login/verify").then();

        // then
        response.statusCode(400).body("error", equalTo(ApiException.INVALID_CODE)).header("Set-Cookie", nullValue());
    }

    @Test
    void shouldRefuseWithoutCountingAttempt_whenVerify_givenRightDigitsAsNumber() throws SQLException {
        // given
        String email = TestPlayers.uniqueEmail("number");
        String guestToken = TestPlayers.createGuest("Number", Rules.KIND_HUMAN);
        String code = requestCode(email);
        List<Object> expected = TestSql.row(dataSource, LOGIN_CODE_ROW, Sessions.hash(email));
        String body = "{\"email\":\"" + email + "\",\"code\":" + Integer.parseInt(code) + "}";

        // when
        ValidatableResponse response = given().cookie(TestPlayers.COOKIE, guestToken)
                .contentType(ContentType.JSON).body(body)
                .when().post("/login/verify").then();

        // then
        response.statusCode(400).body("error", equalTo(ApiException.INVALID_CODE)).header("Set-Cookie", nullValue());
        assertThat(TestSql.row(dataSource, LOGIN_CODE_ROW, Sessions.hash(email)), equalTo(expected));
        verify(guestToken, email, code).statusCode(200).body("account", equalTo(true));
    }

    // Each value follows the email in the body; an empty one leaves the code out.
    @ParameterizedTest
    @ValueSource(strings = {"", ",\"code\":null", ",\"code\":true", ",\"code\":\"12345\"",
            ",\"code\":\"1234567\"", ",\"code\":\" 12345\"", ",\"code\":\"12345a\"",
            ",\"code\":\"\\u0661\\u0662\\u0663\\u0664\\u0665\\u0666\"",
            ",\"code\":\"\\uff11\\uff12\\uff13\\uff14\\uff15\\uff16\"",
            ",\"code\":[\"123456\"]", ",\"code\":{}"})
    void shouldRefuseWithoutCountingAttempt_whenVerify_givenMalformedCode(String codeField) throws SQLException {
        // given
        String email = TestPlayers.uniqueEmail("malformed");
        requestCode(email);
        List<Object> expected = TestSql.row(dataSource, LOGIN_CODE_ROW, Sessions.hash(email));
        String body = "{\"email\":\"" + email + "\"" + codeField + "}";

        // when
        ValidatableResponse response = given().contentType(ContentType.JSON).body(body)
                .when().post("/login/verify").then();

        // then
        response.statusCode(400).body("error", equalTo(ApiException.INVALID_CODE)).header("Set-Cookie", nullValue());
        assertThat(TestSql.row(dataSource, LOGIN_CODE_ROW, Sessions.hash(email)), equalTo(expected));
    }

    // The guest came first: once an account holds the key, no guest can take it.
    @Test
    void shouldRefuseNickTakenAndKeepGuest_whenVerify_givenAccountHoldsGuestsNickKey() throws SQLException {
        // given
        String guestToken = TestPlayers.createGuest("HOLDER", Rules.KIND_AGENT);
        createAccount("Holder", TestPlayers.uniqueEmail("holder"));
        String email = TestPlayers.uniqueEmail("copycat");
        String code = requestCode(email);

        // when
        ValidatableResponse response = verify(guestToken, email, code);

        // then
        response.statusCode(409).body("error", equalTo(ApiException.NICK_TAKEN)).header("Set-Cookie", nullValue());
        given().cookie(TestPlayers.COOKIE, guestToken).when().get("/players/me")
                .then().statusCode(200).body("account", equalTo(false)).body("email", nullValue());
        assertThat(TestSql.count(dataSource, "select count(*) from player where email = ?", email), equalTo(0L));
        assertThat(codeRowsFor(email), equalTo(0L));
    }

    // The guest is BOB_42 in Cyrillic capitals, the account the same in Latin letters.
    @Test
    void shouldRefuseNickTakenAndKeepGuest_whenVerify_givenAccountHoldsLookalikeOfGuestsNick() throws SQLException {
        // given
        String guestToken = TestPlayers.createGuest("\u0412\u041E\u0412_42", Rules.KIND_HUMAN);
        createAccount("Bob_42", TestPlayers.uniqueEmail("bob-42"));
        UUID guestId = playerId(guestToken);
        List<Object> expectedGuest = TestSql.row(dataSource, PLAYER_ROW, guestId);
        String email = TestPlayers.uniqueEmail("lookalike");
        String code = requestCode(email);

        // when
        ValidatableResponse response = verify(guestToken, email, code);

        // then
        response.statusCode(409).body("error", equalTo(ApiException.NICK_TAKEN)).header("Set-Cookie", nullValue());
        assertThat(TestSql.row(dataSource, PLAYER_ROW, guestId), equalTo(expectedGuest));
    }

    // The key a revision before the lookalike map wrote: the nick only in lower case.
    @Test
    void shouldRefuseNickTakenAndKeepGuest_whenVerify_givenGuestKeyWithoutLookalikeMap() throws SQLException {
        // given
        String nick = "\u0412\u041E\u0412_43";
        String guestToken = TestPlayers.createGuest(nick, Rules.KIND_HUMAN);
        UUID guestId = playerId(guestToken);
        TestSql.execute(dataSource, "update player set nick_key = ? where id = ?", nick.toLowerCase(Locale.ROOT),
                guestId);
        createAccount("Bob_43", TestPlayers.uniqueEmail("bob-43"));
        List<Object> expectedGuest = TestSql.row(dataSource, PLAYER_ROW, guestId);
        String email = TestPlayers.uniqueEmail("old-key");
        String code = requestCode(email);

        // when
        ValidatableResponse response = verify(guestToken, email, code);

        // then
        response.statusCode(409).body("error", equalTo(ApiException.NICK_TAKEN)).header("Set-Cookie", nullValue());
        assertThat(TestSql.row(dataSource, PLAYER_ROW, guestId), equalTo(expectedGuest));
    }

    @Test
    void shouldStoreKeyWithLookalikeMap_whenVerify_givenGuestKeyWithoutLookalikeMap() throws SQLException {
        // given
        String nick = "\u0412\u041E\u0412_44";
        String guestToken = TestPlayers.createGuest(nick, Rules.KIND_HUMAN);
        UUID guestId = playerId(guestToken);
        TestSql.execute(dataSource, "update player set nick_key = ? where id = ?", nick.toLowerCase(Locale.ROOT),
                guestId);
        String player = """
                select player.id, player.nick, player.nick_key, player.kind, player.email, player.created_at,
                       now() - player.last_seen_at < interval '1 minute'
                from player where id = ?
                """;
        String email = TestPlayers.uniqueEmail("new-key");
        List<Object> expected = TestSql.row(dataSource, player, guestId);
        expected.set(2, "bob_44");
        expected.set(4, email);
        String code = requestCode(email);

        // when
        ValidatableResponse response = verify(guestToken, email, code);

        // then
        response.statusCode(200).body("account", equalTo(true));
        assertThat(TestSql.row(dataSource, player, guestId), equalTo(expected));
    }

    // The other connection plays an instance that gives an account the
    // guest's nick key after this verify has checked it.
    @Test
    void shouldRefuseNickTakenAndKeepCode_whenVerify_givenAccountTakesNickKeyMeanwhile() throws Exception {
        // given
        String guestToken = TestPlayers.createGuest("LateRacer", Rules.KIND_HUMAN);
        UUID guestId = playerId(guestToken);
        List<Object> expectedGuest = TestSql.row(dataSource, PLAYER_ROW, guestId);
        String email = TestPlayers.uniqueEmail("late-racer");
        String code = requestCode(email);
        List<Object> expectedCode = TestSql.row(dataSource, LOGIN_CODE_ROW, Sessions.hash(email));

        // when
        long lockWaiters;
        ExtractableResponse<Response> response;
        try (ExecutorService executor = Executors.newSingleThreadExecutor();
             Connection otherInstance = dataSource.getConnection()) {
            otherInstance.setAutoCommit(false);
            TestPlayers.insertAccount(otherInstance, "LATERACER", TestPlayers.uniqueEmail("early-racer"));
            Future<ExtractableResponse<Response>> request = executor.submit(
                    () -> verify(guestToken, email, code).extract());
            lockWaiters = TestSql.awaitLockWaiters(dataSource, 1);
            otherInstance.commit();
            response = request.get();
        }

        // then
        assertThat(lockWaiters, equalTo(1L));
        assertThat(response.statusCode(), equalTo(409));
        assertThat(response.body().asString(), equalTo("{\"error\":\"nick_taken\"}"));
        assertThat(response.header("Set-Cookie"), nullValue());
        assertThat(TestSql.row(dataSource, PLAYER_ROW, guestId), equalTo(expectedGuest));
        assertThat(TestSql.row(dataSource, LOGIN_CODE_ROW, Sessions.hash(email)), equalTo(expectedCode));
        given().cookie(TestPlayers.COOKIE, guestToken).when().get("/players/me")
                .then().statusCode(200).body("account", equalTo(false));
    }

    @Test
    void shouldLeaveNothingOfPlayerOrEmail_whenDelete_givenAccountWithResultsCodeAndTwoDevices() throws SQLException {
        // given
        String email = TestPlayers.uniqueEmail("leaving-for-good");
        String firstDevice = createAccount("GoneForGood", email);
        UUID accountId = playerId(firstDevice);
        verify(null, email, requestCode(email)).statusCode(200);
        submitClear(firstDevice, 1, 800, 2);
        submitRun(firstDevice, 2, 70, 1600);
        postCode(email).statusCode(202);
        assertThat(codeRowsFor(email), equalTo(1L));
        String staying = TestPlayers.createGuest("StaysBehind", Rules.KIND_HUMAN);

        // when
        ValidatableResponse response = given().cookie(TestPlayers.COOKIE, firstDevice).when().delete("/players/me")
                .then();

        // then
        response.statusCode(204).header("Set-Cookie", CLEARED_COOKIE);
        assertThat(rowsOf(accountId), equalTo(List.of(0L, 0L, 0L, 0L)));
        assertThat(codeRowsFor(email), equalTo(0L));
        assertThat(tablesHolding(email), equalTo(List.of()));
        given().cookie(TestPlayers.COOKIE, staying).when().get("/players/me").then().statusCode(200);
    }

    @Test
    void shouldRefuseNoSession_whenVerify_givenNoAccountAndNoGuest() throws SQLException {
        // given
        String email = TestPlayers.uniqueEmail("stranger");
        String code = requestCode(email);

        // when
        ValidatableResponse response = verify(null, email, code);

        // then
        response.statusCode(401).body("error", equalTo(ApiException.NO_SESSION)).header("Set-Cookie", nullValue());
        assertThat(TestSql.count(dataSource, "select count(*) from player where email = ?", email), equalTo(0L));
        assertThat(codeRowsFor(email), equalTo(0L));
    }

    @Test
    void shouldRefuseNoSession_whenVerify_givenNewEmailAndAccountOnDevice() throws SQLException {
        // given
        String accountToken = createAccount("Settled", TestPlayers.uniqueEmail("settled"));
        String email = TestPlayers.uniqueEmail("second-address");
        String code = requestCode(email);

        // when
        ValidatableResponse response = verify(accountToken, email, code);

        // then
        response.statusCode(401).body("error", equalTo(ApiException.NO_SESSION));
        assertThat(TestSql.count(dataSource, "select count(*) from player where email = ?", email), equalTo(0L));
        given().cookie(TestPlayers.COOKIE, accountToken).when().get("/players/me").then().statusCode(200);
    }

    @Test
    void shouldDeleteSessionAndClearCookie_whenLogout() throws SQLException {
        // given
        String email = TestPlayers.uniqueEmail("leaver");
        String token = createAccount("Leaver", email);
        int sentBefore = mailbox.getTotalMessagesSent();

        // when
        ExtractableResponse<Response> response = given().cookie(TestPlayers.COOKIE, token)
                .when().post("/logout").then().extract();

        // then
        assertThat(response.statusCode(), equalTo(204));
        assertThat(mailbox.getTotalMessagesSent(), equalTo(sentBefore));
        assertThat(response.header("Set-Cookie"), equalTo(CLEARED_COOKIE));
        assertThat(TestSql.count(dataSource, "select count(*) from session where token_hash = ?", Sessions.hash(token)),
                equalTo(0L));
        assertThat(TestSql.count(dataSource, "select count(*) from player where email = ?", email), equalTo(1L));
        given().cookie(TestPlayers.COOKIE, token).when().get("/players/me")
                .then().statusCode(401).body("error", equalTo(ApiException.NO_SESSION));
    }

    @Test
    void shouldKeepOtherDevice_whenLogout_givenAccountOnTwoDevices() {
        // given
        String email = TestPlayers.uniqueEmail("two-devices");
        String firstDevice = createAccount("TwoDevices", email);
        String secondDevice = tokenIn(verify(null, email, requestCode(email)).statusCode(200).extract());

        // when
        given().cookie(TestPlayers.COOKIE, firstDevice).when().post("/logout").then().statusCode(204);

        // then
        given().cookie(TestPlayers.COOKIE, secondDevice).when().get("/players/me")
                .then().statusCode(200).body("email", equalTo(email));
    }

    @Test
    void shouldRefuse_whenLogout_givenNoSession() {
        // when
        ValidatableResponse response = given().when().post("/logout").then();

        // then
        response.statusCode(401).body("error", equalTo(ApiException.NO_SESSION)).header("Set-Cookie", nullValue());
    }

    private static ValidatableResponse postCode(String email) {
        return given().contentType(ContentType.JSON).body(Map.of("email", email)).when().post("/login/code").then();
    }

    private static ValidatableResponse verify(String token, String email, String code) {
        RequestSpecification request = null == token ? given() : given().cookie(TestPlayers.COOKIE, token);
        return request.contentType(ContentType.JSON).body(Map.of("email", email, "code", code))
                .when().post("/login/verify").then();
    }

    private String requestCode(String email) {
        postCode(email).statusCode(202);
        return sentCode(email);
    }

    private String sentCode(String email) {
        return codeIn(mailbox.getMailMessagesSentTo(email).getLast().getText());
    }

    private String createAccount(String nick, String email) {
        return createAccount(nick, Rules.KIND_HUMAN, email);
    }

    private String createAccount(String nick, String kind, String email) {
        String guestToken = TestPlayers.createGuest(nick, kind);
        return tokenIn(verify(guestToken, email, requestCode(email)).statusCode(200).extract());
    }

    private static void submitClear(String token, int level, int ticks, int chests) {
        given().cookie(TestPlayers.COOKIE, token).contentType(ContentType.JSON)
                .body(Map.of("level", level, "shape", Rules.SHAPE_PORTRAIT, "revision", Rules.currentRevision(level),
                        "ticks", ticks, "chests", chests, "chestsTotal", 5))
                .when().post("/clears").then().statusCode(201);
    }

    private static void submitRun(String token, int levelsCleared, int score, int ticks) {
        given().cookie(TestPlayers.COOKIE, token).contentType(ContentType.JSON)
                .body(Map.of("levelsCleared", levelsCleared, "score", score, "ticks", ticks))
                .when().post("/runs").then().statusCode(201);
    }

    // The player id comes second in both, so a moved row differs only there.
    private List<List<Object>> clearsOf(UUID playerId) throws SQLException {
        return TestSql.rows(dataSource, """
                select id, player_id, level, shape, revision, ticks, chests, chests_total, created_at
                from level_clear where player_id = ? order by id
                """, playerId);
    }

    private List<List<Object>> runsOf(UUID playerId) throws SQLException {
        return TestSql.rows(dataSource, """
                select id, player_id, levels_cleared, score, ticks_total, finished_at
                from game_run where player_id = ? order by id
                """, playerId);
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

    private List<Object> rowsOf(UUID playerId) throws SQLException {
        return TestSql.row(dataSource, """
                select (select count(*) from player where id = ?),
                       (select count(*) from session where player_id = ?),
                       (select count(*) from level_clear where player_id = ?),
                       (select count(*) from game_run where player_id = ?)
                """, playerId, playerId, playerId, playerId);
    }


    private UUID playerId(String token) throws SQLException {
        return (UUID) TestSql.row(dataSource, "select player_id from session where token_hash = ?",
                Sessions.hash(token)).getFirst();
    }

    private long codeRowsFor(String email) throws SQLException {
        return TestSql.count(dataSource, "select count(*) from login_code where email_hash = ?", Sessions.hash(email));
    }

    private void seedExpiredCode(String email) throws SQLException {
        TestSql.execute(dataSource, """
                insert into login_code (email_hash, code_hash, attempts, expires_at, created_at)
                values (?, 'stale', 0, now() - interval '1 second', now() - interval '601 seconds')
                """, Sessions.hash(email));
    }

    // Every table, so a table added later is checked too. position(), not
    // like, because _ in an address is a like wildcard.
    private List<String> tablesHolding(String text) throws SQLException {
        String tableNames = (String) TestSql.row(dataSource, """
                select string_agg(format('%I', table_name), ',' order by table_name)
                from information_schema.tables where table_schema = 'public' and table_type = 'BASE TABLE'
                """).getFirst();
        List<String> holding = new ArrayList<>();
        for (String table : tableNames.split(",")) {
            String sql = "select count(*) from " + table
                    + " stored where position(lower(?) in lower(stored::text)) > 0";
            if (0 < TestSql.count(dataSource, sql, text)) {
                holding.add(table);
            }
        }
        return holding;
    }

    private void seedSentMails(int count, String age) throws SQLException {
        TestSql.execute(dataSource, """
                insert into login_mail (email_hash, sent_at)
                select 'seeded', now() - ?::interval from generate_series(1, ?)
                """, age, count);
    }

    private static String codeIn(String text) {
        Matcher matcher = CODE.matcher(text);
        assertThat(matcher.find(), equalTo(true));
        return matcher.group();
    }

    private static String otherCode(String code) {
        return "%06d".formatted((Integer.parseInt(code) + 1) % 1_000_000);
    }

    private static String tokenIn(ExtractableResponse<Response> response) {
        String cookie = response.header("Set-Cookie");
        assertThat(cookie, matchesPattern(COOKIE_PATTERN));
        Matcher matcher = Pattern.compile(COOKIE_PATTERN).matcher(cookie);
        assertThat(matcher.matches(), equalTo(true));
        return matcher.group(1);
    }


    private static Map<Integer, Long> inParallel(List<Callable<Integer>> requests) throws InterruptedException {
        Map<Integer, Long> statusCounts;
        try (ExecutorService executor = Executors.newFixedThreadPool(requests.size())) {
            statusCounts = executor.invokeAll(requests).stream()
                    .collect(Collectors.groupingBy(Future::resultNow, Collectors.counting()));
        }
        return statusCounts;
    }
}
