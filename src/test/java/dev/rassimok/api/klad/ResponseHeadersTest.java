package dev.rassimok.api.klad;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class ResponseHeadersTest {

    private static final String CONTENT_SECURITY_POLICY = "default-src 'none'; frame-ancestors 'none'";

    @TestHTTPResource("/clears")
    URI clearsUri;

    @TestHTTPResource("/players")
    URI playersUri;

    static Stream<Arguments> errors() {
        return Stream.of(
                Arguments.of("GET", "/nowhere", null, null, 404, "not_found"),
                Arguments.of("GET", "/q/nowhere", null, null, 404, "not_found"),
                Arguments.of("GET", "/players/nowhere", null, null, 404, "not_found"),
                Arguments.of("PUT", "/players/me", null, null, 405, "method_not_allowed"),
                Arguments.of("POST", "/clears", "text/plain", "level=1", 415, "unsupported_media_type"),
                Arguments.of("POST", "/clears", "application/octet-stream", "{\"level\":1}", 415, "unsupported_media_type"),
                Arguments.of("PATCH", "/players/me", "application/octet-stream", "{\"nick\":\"Bob\"}", 415, "unsupported_media_type"),
                Arguments.of("POST", "/players", "application/json", "{\"nick\":", 400, "bad_request"),
                Arguments.of("POST", "/players", "application/json", "{\"nick\":\"Bob\",\"kind\":[1]}", 400, "invalid_kind"),
                Arguments.of("POST", "/players", "application/json", "{\"nick\":12345,\"kind\":\"human\"}", 400, "invalid_nick"),
                Arguments.of("POST", "/clears", "application/json", "{\"level\":", 400, "bad_request"),
                Arguments.of("POST", "/runs", "application/json", "[1,", 400, "bad_request"),
                Arguments.of("GET", "/players/me", null, null, 401, "no_session"),
                Arguments.of("GET", "/boards/level?level=x", null, null, 400, "invalid_query"));
    }

    // A valid player body, so only the size can refuse it. The input stream
    // has no known length, so it goes out chunked, as the proxy forwards it.
    static Stream<Arguments> bodiesOverLimit() {
        byte[] body = ("{\"nick\":\"Bob\",\"kind\":\"human\",\"padding\":\"" + "x".repeat(5000) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        return Stream.of(
                Arguments.of(Named.of("Content-Length", HttpRequest.BodyPublishers.ofByteArray(body))),
                Arguments.of(Named.of("chunked",
                        HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))));
    }

    static Stream<Arguments> successes() {
        return Stream.of(
                Arguments.of("/status"),
                Arguments.of("/q/health"),
                Arguments.of("/q/health/ready"),
                Arguments.of("/boards/records"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("errors")
    void shouldAnswerJsonErrorWithHeaders_whenRequest_givenError(String method, String path, String contentType,
                                                                 String body, int status, String code) {
        // given
        RequestSpecification request = null == contentType
                ? given()
                : given().contentType(contentType).body(body.getBytes(StandardCharsets.UTF_8));

        // when
        ValidatableResponse response = request.when().request(method, path).then();

        // then
        assertSecurityHeaders(response.statusCode(status))
                .contentType(ContentType.JSON)
                .body("", aMapWithSize(1))
                .body("error", equalTo(code));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("successes")
    void shouldSendSecurityHeaders_whenGet_givenSuccess(String path) {
        // when
        ValidatableResponse response = given().when().get(path).then();

        // then
        assertSecurityHeaders(response.statusCode(200));
    }

    @Test
    void shouldAnswerUnsupportedMediaType_whenSubmitClear_givenBodyWithoutContentType() throws Exception {
        // given
        HttpRequest request = HttpRequest.newBuilder(clearsUri)
                .POST(HttpRequest.BodyPublishers.ofString("{\"level\":1}"))
                .build();

        // when
        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newHttpClient()) {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        }

        // then
        assertThat(response.statusCode(), equalTo(415));
        assertThat(response.body(), equalTo("{\"error\":\"unsupported_media_type\"}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bodiesOverLimit")
    void shouldRefuseWithHeadersAndNoBody_whenCreatePlayer_givenBodyOverLimit(HttpRequest.BodyPublisher body)
            throws Exception {
        // given
        HttpRequest request = HttpRequest.newBuilder(playersUri)
                .header("Content-Type", "application/json")
                .POST(body)
                .build();

        // when
        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        }

        // then
        assertThat(response.statusCode(), equalTo(413));
        assertThat(response.headers().firstValue("Cache-Control"), equalTo(Optional.of("no-store")));
        assertThat(response.headers().firstValue("X-Content-Type-Options"), equalTo(Optional.of("nosniff")));
        assertThat(response.headers().firstValue("Content-Security-Policy"), equalTo(Optional.of(CONTENT_SECURITY_POLICY)));
        assertThat(response.body(), equalTo(""));
    }

    @Test
    void shouldSendSecurityHeaders_whenCreatePlayerAndDeletePlayer() {
        // given
        ValidatableResponse created = given()
                .contentType(ContentType.JSON)
                .body("{\"nick\":\"Headers\",\"kind\":\"human\"}")
                .when().post("/players")
                .then();
        String token = created.extract().cookie(TestPlayers.COOKIE);

        // when
        ValidatableResponse deleted = given().cookie(TestPlayers.COOKIE, token).when().delete("/players/me").then();

        // then
        assertSecurityHeaders(created.statusCode(201));
        assertSecurityHeaders(deleted.statusCode(204));
    }

    @Test
    void shouldNotAllowOtherOrigins_whenGetBoard_givenOriginHeader() {
        // when
        ValidatableResponse response = given()
                .header("Origin", "https://example.com")
                .when().get("/boards/records")
                .then();

        // then
        response.statusCode(200).header("Access-Control-Allow-Origin", nullValue());
    }

    private static ValidatableResponse assertSecurityHeaders(ValidatableResponse response) {
        return response
                .header("Cache-Control", "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", CONTENT_SECURITY_POLICY);
    }
}
