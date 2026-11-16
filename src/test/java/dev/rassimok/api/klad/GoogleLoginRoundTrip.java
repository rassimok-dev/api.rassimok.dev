package dev.rassimok.api.klad;

import io.restassured.http.ContentType;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * Start, Google's return and the finish, over HTTP only. The JVM test runs it
 * against the fake Google over HTTP, the native test against the binary,
 * which then calls the fake over HTTPS. Not a test class itself, because the
 * two need the fake set up differently.
 */
abstract class GoogleLoginRoundTrip {

    @Test
    void shouldTurnGuestIntoAccount_whenFinish_givenGoogleProvedNewAddress() {
        // given
        String nick = TestPlayers.uniqueNick("Googler");
        String guestToken = TestPlayers.createGuest(nick, Rules.KIND_AGENT);
        String email = TestPlayers.uniqueEmail("google-round-trip");
        String secret = GoogleSignIns.provenAtGoogle(email.toUpperCase(Locale.ROOT));

        // when
        ExtractableResponse<Response> finished = GoogleSignIns.finish(secret, guestToken).extract();
        String accountToken = GoogleSignIns.sessionTokenIn(finished);

        // then
        assertThat(finished.statusCode(), equalTo(200));
        assertThat(finished.as(Map.class), equalTo(Map.of("nick", nick, "kind", Rules.KIND_AGENT, "account", true,
                "email", email, "merged", false)));
        assertThat(finished.headers().getValues("Set-Cookie"), hasItem(GoogleSignIns.CLEARED_COOKIE));
        assertThat(accountToken, not(equalTo(guestToken)));
        given().cookie(TestPlayers.COOKIE, accountToken).when().get("/players/me")
                .then().statusCode(200).body("email", equalTo(email)).body("nick", equalTo(nick));
        given().cookie(TestPlayers.COOKIE, guestToken).when().get("/players/me")
                .then().statusCode(401).body("error", equalTo(ApiException.NO_SESSION));
        GoogleSignIns.finish(secret, accountToken)
                .statusCode(400).body("", equalTo(Map.of("error", ApiException.NO_GOOGLE_LOGIN)));
    }

    @Test
    void shouldSignSecondDeviceIn_whenFinish_givenAccountMadeThroughGoogle() {
        // given
        String nick = TestPlayers.uniqueNick("Twice");
        String guestToken = TestPlayers.createGuest(nick, Rules.KIND_HUMAN);
        String email = TestPlayers.uniqueEmail("google-twice");
        String firstDevice = GoogleSignIns.sessionTokenIn(
                GoogleSignIns.finish(GoogleSignIns.provenAtGoogle(email), guestToken).statusCode(200).extract());
        String secret = GoogleSignIns.provenAtGoogle(email);

        // when
        ValidatableResponse finished = GoogleSignIns.finish(secret, null);

        // then
        finished.statusCode(200).body("", equalTo(Map.of("nick", nick, "kind", Rules.KIND_HUMAN, "account", true,
                "email", email, "merged", false)));
        String secondDevice = GoogleSignIns.sessionTokenIn(finished.extract());
        assertThat(secondDevice, not(equalTo(firstDevice)));
        given().cookie(TestPlayers.COOKIE, firstDevice).when().get("/players/me")
                .then().statusCode(200).body("email", equalTo(email));
        given().cookie(TestPlayers.COOKIE, secondDevice).when().get("/players/me")
                .then().statusCode(200).body("email", equalTo(email));
    }

    @Test
    void shouldFinishWithoutSecondTrip_whenFinish_givenNoSessionAndThenGuestCreated() {
        // given
        String email = TestPlayers.uniqueEmail("google-later-guest");
        String secret = GoogleSignIns.provenAtGoogle(email);
        ValidatableResponse refused = GoogleSignIns.finish(secret, null);
        String nick = TestPlayers.uniqueNick("Late");
        String guestToken = given().contentType(ContentType.JSON).body(Map.of("nick", nick, "kind", Rules.KIND_HUMAN))
                .when().post("/players").then().statusCode(201).extract().cookie(TestPlayers.COOKIE);

        // when
        ValidatableResponse finished = GoogleSignIns.finish(secret, guestToken);

        // then
        refused.statusCode(401).body("", equalTo(Map.of("error", ApiException.NO_SESSION)));
        finished.statusCode(200).body("", equalTo(Map.of("nick", nick, "kind", Rules.KIND_HUMAN, "account", true,
                "email", email, "merged", false)));
    }

    @Test
    void shouldSendBackToGameAndClearCookie_whenReturnFromGoogle_givenUnverifiedAddress() {
        // given
        GoogleSignIns.Started started = GoogleSignIns.started();
        String email = TestPlayers.uniqueEmail("google-unverified");

        // when
        ValidatableResponse returned = GoogleSignIns.returnFromGoogle(started,
                started.code(email, false, FakeGoogle.ANSWER_TOKEN));

        // then
        returned.statusCode(302).header("Location", GoogleSignIns.FAILED)
                .header("Set-Cookie", GoogleSignIns.CLEARED_COOKIE);
        GoogleSignIns.finish(started.secret(), null)
                .statusCode(400).body("", equalTo(Map.of("error", ApiException.NO_GOOGLE_LOGIN)));
    }
}
