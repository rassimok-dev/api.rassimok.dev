package dev.rassimok.api.klad;

import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * The browser's side of a sign-in with Google, over HTTP only, so the native
 * test can use it too. Redirects are never followed: they lead to Google and
 * to rassimok.dev.
 */
final class GoogleSignIns {

    static final String COOKIE = "klad_oauth";
    static final String GAME = "https://rassimok.dev/klad/";
    static final String SIGNED_IN = GAME + "#login=google";
    static final String FAILED = GAME + "#login=google-failed";
    static final String UNAVAILABLE = GAME + "#login=google-unavailable";
    static final String COOKIE_ATTRIBUTES = "; Path=/klad/api/login/google; HttpOnly; Secure; SameSite=Lax; ";
    static final String CLEARED_COOKIE = COOKIE + "=" + COOKIE_ATTRIBUTES + "Max-Age=0";
    private static final Pattern SET_COOKIE = Pattern.compile(
            COOKIE + "=([A-Za-z0-9_-]{43})" + Pattern.quote(COOKIE_ATTRIBUTES) + "Max-Age=600");

    /** A started sign-in: the cookie's secret and the query Google got. */
    record Started(String secret, Map<String, String> query) {

        String state() {
            return query.get("state");
        }

        String code(String email) {
            return code(email, true, FakeGoogle.ANSWER_TOKEN);
        }

        String code(String email, Object emailVerified, String answer) {
            return FakeGoogle.code(email, emailVerified, query.get("nonce"), query.get("code_challenge"), answer);
        }
    }

    private GoogleSignIns() {
    }

    static ExtractableResponse<Response> start() {
        return given().redirects().follow(false).when().get("/login/google").then().extract();
    }

    static Started started() {
        ExtractableResponse<Response> response = start();
        assertThat(response.statusCode(), equalTo(302));
        return new Started(secretIn(response.header("Set-Cookie")), queryOf(response.header("Location")));
    }

    static ValidatableResponse returnFromGoogle(String secret, Map<String, String> query) {
        RequestSpecification request = given().redirects().follow(false);
        if (null != secret) {
            request.cookie(COOKIE, secret);
        }
        return request.queryParams(query).when().get("/login/google/callback").then();
    }

    static ValidatableResponse returnFromGoogle(Started started, String code) {
        return returnFromGoogle(started.secret(), Map.of("state", started.state(), "code", code));
    }

    static ValidatableResponse finish(String secret, String sessionToken) {
        RequestSpecification request = given();
        if (null != secret) {
            request.cookie(COOKIE, secret);
        }
        if (null != sessionToken) {
            request.cookie(TestPlayers.COOKIE, sessionToken);
        }
        return request.when().post("/login/google/finish").then();
    }

    // Start, Google proves the address, back to the game: ready to finish.
    static String provenAtGoogle(String email) {
        Started started = started();
        returnFromGoogle(started, started.code(email)).statusCode(302).header("Location", SIGNED_IN);
        return started.secret();
    }

    static String secretIn(String setCookie) {
        Matcher matcher = SET_COOKIE.matcher(null == setCookie ? "" : setCookie);
        assertThat(matcher.matches(), equalTo(true));
        return matcher.group(1);
    }

    static Map<String, String> queryOf(String location) {
        Map<String, String> query = new HashMap<>();
        for (String pair : location.substring(location.indexOf('?') + 1).split("&")) {
            int equals = pair.indexOf('=');
            query.put(pair.substring(0, equals), URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
        }
        return query;
    }

    static String sessionTokenIn(ExtractableResponse<Response> response) {
        return response.cookie(TestPlayers.COOKIE);
    }
}
