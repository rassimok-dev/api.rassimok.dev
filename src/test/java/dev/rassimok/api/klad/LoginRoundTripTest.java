package dev.rassimok.api.klad;

import io.quarkus.arc.Arc;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * HTTP only, so the native integration test can run it against the binary;
 * there only the way the sent code is read differs. Integration tests allow
 * no injection, so the mailbox is looked up instead.
 */
@QuarkusTest
class LoginRoundTripTest {

    private static final Pattern CODE = Pattern.compile("(?<![0-9])[0-9]{6}(?![0-9])");

    @Test
    void shouldTurnGuestIntoAccountAndLogOut_whenVerifyAndLogout() throws IOException, InterruptedException {
        // given
        String guestToken = TestPlayers.createGuest("RoundTripper", Rules.KIND_AGENT);
        String email = TestPlayers.uniqueEmail("round-trip");
        requestCode(email);
        String code = sentCode(email);

        // when
        ValidatableResponse verified = given().cookie(TestPlayers.COOKIE, guestToken).contentType(ContentType.JSON)
                .body(Map.of("email", email, "code", code))
                .when().post("/login/verify")
                .then();
        String accountToken = verified.extract().cookie(TestPlayers.COOKIE);
        ValidatableResponse me = given().cookie(TestPlayers.COOKIE, accountToken).when().get("/players/me").then();
        ValidatableResponse loggedOut = given().cookie(TestPlayers.COOKIE, accountToken).when().post("/logout").then();

        // then
        verified.statusCode(200).body("", equalTo(Map.of("nick", "RoundTripper", "kind", Rules.KIND_AGENT,
                "account", true, "email", email, "merged", false)));
        assertThat(accountToken, not(equalTo(guestToken)));
        me.statusCode(200).body("email", equalTo(email));
        loggedOut.statusCode(204);
        given().cookie(TestPlayers.COOKIE, accountToken).when().get("/players/me")
                .then().statusCode(401).body("error", equalTo(ApiException.NO_SESSION));
    }

    @Test
    void shouldLogSecondDeviceIn_whenVerify_givenAccountAndNoSession() throws IOException, InterruptedException {
        // given
        String guestToken = TestPlayers.createGuest("SecondDevice", Rules.KIND_AGENT);
        String email = TestPlayers.uniqueEmail("second-device");
        requestCode(email);
        String firstDevice = given().cookie(TestPlayers.COOKIE, guestToken).contentType(ContentType.JSON)
                .body(Map.of("email", email, "code", sentCode(email)))
                .when().post("/login/verify")
                .then().statusCode(200)
                .extract().cookie(TestPlayers.COOKIE);
        requestCode(email);

        // when
        ValidatableResponse verified = given().contentType(ContentType.JSON)
                .body(Map.of("email", email, "code", sentCode(email)))
                .when().post("/login/verify")
                .then();

        // then
        verified.statusCode(200).body("nick", equalTo("SecondDevice")).body("account", equalTo(true));
        String secondDevice = verified.extract().cookie(TestPlayers.COOKIE);
        assertThat(secondDevice, not(equalTo(firstDevice)));
        given().cookie(TestPlayers.COOKIE, secondDevice).when().get("/players/me")
                .then().statusCode(200).body("email", equalTo(email));
        given().cookie(TestPlayers.COOKIE, firstDevice).when().get("/players/me")
                .then().statusCode(200).body("email", equalTo(email));
    }

    @Test
    void shouldRefuseNickTaken_whenCreatePlayerAndRename_givenAccountHoldsNickKey()
            throws IOException, InterruptedException {
        // given
        String holderToken = TestPlayers.createGuest("NativeHolder", Rules.KIND_HUMAN);
        String email = TestPlayers.uniqueEmail("native-holder");
        requestCode(email);
        given().cookie(TestPlayers.COOKIE, holderToken).contentType(ContentType.JSON)
                .body(Map.of("email", email, "code", sentCode(email)))
                .when().post("/login/verify")
                .then().statusCode(200).body("account", equalTo(true));
        String guestToken = TestPlayers.createGuest("NativeGuest", Rules.KIND_AGENT);

        // when
        ValidatableResponse created = given().contentType(ContentType.JSON)
                .body(Map.of("nick", "NATIVEHOLDER", "kind", Rules.KIND_AGENT))
                .when().post("/players")
                .then();
        ValidatableResponse renamed = given().cookie(TestPlayers.COOKIE, guestToken).contentType(ContentType.JSON)
                .body(Map.of("nick", "nativeholder"))
                .when().patch("/players/me")
                .then();

        // then
        created.statusCode(409).body("", equalTo(Map.of("error", ApiException.NICK_TAKEN)));
        renamed.statusCode(409).body("", equalTo(Map.of("error", ApiException.NICK_TAKEN)));
        given().cookie(TestPlayers.COOKIE, guestToken).when().get("/players/me")
                .then().statusCode(200).body("nick", equalTo("NativeGuest"));
    }

    protected String sentCode(String email) throws IOException, InterruptedException {
        MockMailbox mailbox = Arc.container().instance(MockMailbox.class).get();
        return codeIn(mailbox.getMailMessagesSentTo(email).getLast().getText());
    }

    static String codeIn(String text) {
        Matcher matcher = CODE.matcher(null == text ? "" : text);
        assertThat(matcher.find(), equalTo(true));
        return matcher.group();
    }

    private static void requestCode(String email) {
        given().contentType(ContentType.JSON).body(Map.of("email", email))
                .when().post("/login/code")
                .then().statusCode(202).body("", equalTo(Map.of()));
    }
}
