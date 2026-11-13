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
import org.junit.jupiter.params.provider.ValueSource;

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
    private static final String PLAYER_BODY = "{\"nick\":\"Bob\",\"kind\":\"human\"}";
    // Never a valid email or code: on the binary these tests run without the
    // mock mailbox, so they must never send a mail.
    private static final String CODE_BODY = "{\"email\":\"nobody\"}";
    private static final String VERIFY_BODY = "{\"email\":\"nobody@example.com\",\"code\":\"123456\"}";
    private static final String READY_BODY = "{\"ready\":true}";
    private static final String LEVEL_BODY = "{\"level\":2,\"shape\":\"portrait\"}";

    @TestHTTPResource("/clears")
    URI clearsUri;

    @TestHTTPResource("/players")
    URI playersUri;

    @TestHTTPResource("/players/me")
    URI playerUri;

    @TestHTTPResource("/logout")
    URI logoutUri;

    static Stream<Arguments> errors() {
        return Stream.of(
                Arguments.of("GET", "/nowhere", null, null, 404, "not_found"),
                Arguments.of("GET", "/q/nowhere", null, null, 404, "not_found"),
                Arguments.of("GET", "/players/nowhere", null, null, 404, "not_found"),
                Arguments.of("PUT", "/players/me", null, null, 405, "method_not_allowed"),
                Arguments.of("POST", "/clears", "text/plain", "level=1", 415, "unsupported_media_type"),
                Arguments.of("POST", "/clears", "application/octet-stream", "{\"level\":1}", 415, "unsupported_media_type"),
                Arguments.of("PATCH", "/players/me", "application/octet-stream", "{\"nick\":\"Bob\"}", 415, "unsupported_media_type"),
                Arguments.of("POST", "/players", "*/*", PLAYER_BODY, 415, "unsupported_media_type"),
                Arguments.of("POST", "/players", "application/*", PLAYER_BODY, 415, "unsupported_media_type"),
                Arguments.of("POST", "/players", "application/json; version=2", PLAYER_BODY, 415, "unsupported_media_type"),
                Arguments.of("POST", "/clears", "garbage", "{\"level\":1}", 415, "unsupported_media_type"),
                Arguments.of("POST", "/players", "application/json", "{\"nick\":", 400, "bad_request"),
                Arguments.of("POST", "/players", "application/json", "{\"nick\":\"Bob\",\"kind\":[1]}", 400, "invalid_kind"),
                Arguments.of("POST", "/players", "application/json", "{\"nick\":12345,\"kind\":\"human\"}", 400, "invalid_nick"),
                Arguments.of("POST", "/clears", "application/json", "{\"level\":", 400, "bad_request"),
                Arguments.of("POST", "/runs", "application/json", "[1,", 400, "bad_request"),
                Arguments.of("GET", "/players/me", null, null, 401, "no_session"),
                Arguments.of("GET", "/boards/level?level=x", null, null, 400, "invalid_query"),
                Arguments.of("POST", "/login/code", "application/json", CODE_BODY, 400, "invalid_email"),
                Arguments.of("POST", "/login/verify", "application/json", VERIFY_BODY, 400, "invalid_code"),
                Arguments.of("POST", "/logout", null, null, 401, "no_session"),
                Arguments.of("POST", "/presence", null, null, 401, "no_session"),
                Arguments.of("GET", "/rooms/AAAAAAAAAA", null, null, 401, "no_session"),
                Arguments.of("GET", "/rooms", null, null, 405, "method_not_allowed"),
                Arguments.of("POST", "/rooms/AAAAAAAAAA/ready", "text/plain", "ready", 415, "unsupported_media_type"),
                Arguments.of("POST", "/rooms/AAAAAAAAAA/level", "application/*", "{\"level\":1}", 415, "unsupported_media_type"),
                Arguments.of("POST", "/rooms/AAAAAAAAAA/ready", "application/json", "{\"ready\":", 400, "bad_request"));
    }

    // Every endpoint that reads a JSON body belongs here. Routing already
    // refuses octet-stream; only the filter refuses a body with no type.
    static Stream<Arguments> bodiesWithoutJsonType() {
        return Stream.of(
                Arguments.of("POST", "/players", null, PLAYER_BODY),
                Arguments.of("POST", "/players", "application/octet-stream", PLAYER_BODY),
                Arguments.of("PATCH", "/players/me", null, "{\"nick\":\"Bob\"}"),
                Arguments.of("PATCH", "/players/me", "application/octet-stream", "{\"nick\":\"Bob\"}"),
                Arguments.of("POST", "/clears", null, "{\"level\":1}"),
                Arguments.of("POST", "/clears", "application/octet-stream", "{\"level\":1}"),
                Arguments.of("POST", "/runs", null, "{\"levelsCleared\":1}"),
                Arguments.of("POST", "/runs", "application/octet-stream", "{\"levelsCleared\":1}"),
                Arguments.of("POST", "/login/code", null, CODE_BODY),
                Arguments.of("POST", "/login/code", "application/octet-stream", CODE_BODY),
                Arguments.of("POST", "/login/verify", null, VERIFY_BODY),
                Arguments.of("POST", "/login/verify", "application/octet-stream", VERIFY_BODY),
                Arguments.of("POST", "/rooms/AAAAAAAAAA/ready", null, READY_BODY),
                Arguments.of("POST", "/rooms/AAAAAAAAAA/ready", "application/octet-stream", READY_BODY),
                Arguments.of("POST", "/rooms/AAAAAAAAAA/level", null, LEVEL_BODY),
                Arguments.of("POST", "/rooms/AAAAAAAAAA/level", "application/octet-stream", LEVEL_BODY));
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
    void shouldIgnoreBody_whenGetBoard_givenBodyWithMalformedContentType() {
        // when
        ValidatableResponse response = given()
                .contentType("garbage")
                .body("x".getBytes(StandardCharsets.UTF_8))
                .when().get("/boards/records")
                .then();

        // then
        assertSecurityHeaders(response.statusCode(200));
    }

    // The proxy may forward a bodiless request as an empty chunked body.
    @Test
    void shouldDeletePlayer_whenDeletePlayer_givenEmptyChunkedBodyWithoutContentType() throws Exception {
        // given
        String token = TestPlayers.createGuest("Chunked", Rules.KIND_HUMAN);
        HttpRequest request = HttpRequest.newBuilder(playerUri)
                .header("Cookie", TestPlayers.COOKIE + "=" + token)
                .method("DELETE", HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(new byte[0])))
                .build();

        // when
        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        }

        // then
        assertThat(response.statusCode(), equalTo(204));
        given().cookie(TestPlayers.COOKIE, token).when().get("/players/me")
                .then().statusCode(401).body("error", equalTo(ApiException.NO_SESSION));
    }

    @Test
    void shouldLogOut_whenLogout_givenEmptyChunkedBodyWithoutContentType() throws Exception {
        // given
        String token = TestPlayers.createGuest("Chunked", Rules.KIND_HUMAN);
        HttpRequest request = HttpRequest.newBuilder(logoutUri)
                .header("Cookie", TestPlayers.COOKIE + "=" + token)
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(new byte[0])))
                .build();

        // when
        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        }

        // then
        assertThat(response.statusCode(), equalTo(204));
        assertThat(response.headers().firstValue("Content-Type"), equalTo(Optional.empty()));
        assertThat(response.body(), equalTo(""));
        given().cookie(TestPlayers.COOKIE, token).when().get("/players/me")
                .then().statusCode(401).body("error", equalTo(ApiException.NO_SESSION));
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

    @Test
    void shouldAnswerUnsupportedMediaType_whenSubmitClear_givenNoBodyAndNoContentType() throws Exception {
        // given
        HttpRequest request = HttpRequest.newBuilder(clearsUri)
                .POST(HttpRequest.BodyPublishers.noBody())
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

    // Sent with the JDK client: RestAssured cannot send a body without a type.
    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("bodiesWithoutJsonType")
    void shouldAnswerUnsupportedMediaType_whenRequest_givenBodyWithoutJsonContentType(String method, String path,
                                                                                      String contentType, String body)
            throws Exception {
        // given
        HttpRequest.Builder builder = HttpRequest.newBuilder(playersUri.resolve(path))
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        HttpRequest request = null == contentType ? builder.build() : builder.header("Content-Type", contentType).build();

        // when
        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newHttpClient()) {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        }

        // then
        assertThat(response.statusCode(), equalTo(415));
        assertThat(response.body(), equalTo("{\"error\":\"unsupported_media_type\"}"));
    }

    // Sent with the JDK client, which adds no charset of its own.
    @ParameterizedTest
    @ValueSource(strings = {"application/json", "application/json; charset=utf-8", "Application/JSON;Charset=UTF-8"})
    void shouldCreatePlayer_whenCreatePlayer_givenJsonContentType(String contentType) throws Exception {
        // given
        HttpRequest request = HttpRequest.newBuilder(playersUri)
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(PLAYER_BODY))
                .build();

        // when
        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newHttpClient()) {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        }

        // then
        assertThat(response.statusCode(), equalTo(201));
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
