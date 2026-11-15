package dev.rassimok.api.klad;

import dev.rassimok.api.logging.JsonLogProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;

/**
 * No log line may hold the text of a chat message, as for an email address.
 * The profile writes the production JSON format to a file, so the formatted
 * lines are read as Cloud Logging would get them. JVM only: a native test
 * cannot see the binary's log from here.
 */
@QuarkusTest
@TestProfile(JsonLogProfile.class)
class ChatLogTest {

    private static final String SENTINEL = "Unexpected error";

    @TestHTTPResource("/rooms")
    URI roomsUri;

    @Test
    void shouldWriteNoLineWithText_whenSend_givenAcceptedAndRefusedMessages() throws Exception {
        // given
        String marker = "Chat" + UUID.randomUUID().toString().replace("-", "");
        String host = TestPlayers.createGuest("LogHost", Rules.KIND_HUMAN);
        String id = given().cookie(TestPlayers.COOKIE, host).when().post("/rooms")
                .then().statusCode(201).extract().path("id");
        String messagesPath = "/rooms/" + id + "/messages";
        String markerBody = "{\"text\":\"" + marker + "\"}";
        List<String> records = new CopyOnWriteArrayList<>();
        Handler collector = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(describe(record));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        long sentinelsBefore = count(readLog(), SENTINEL);
        Logger root = Logger.getLogger("");
        root.addHandler(collector);

        // when
        try {
            assertThat(sendChunked(id, host, "{\"text\":\"" + marker + "\",\"padding\":\"" + "x".repeat(5000) + "\"}"),
                    equalTo(413));
            // The server logs after the 413 has gone out, so give it a moment.
            Thread.sleep(1000);
            for (int i = 0; i < Rules.CHAT_MESSAGES_PER_WINDOW; i++) {
                send(host, messagesPath, markerBody).statusCode(201);
            }
            send(host, messagesPath, markerBody).statusCode(429);
            send(host, messagesPath, "{\"text\":\"" + marker + "x".repeat(Rules.CHAT_TEXT_LENGTH_MAX) + "\"}")
                    .statusCode(400);
            send(host, messagesPath, "{\"text\":\"" + marker + "\\u200B\"}").statusCode(400);
            send(host, messagesPath, "{\"text\":\"" + marker + "\\nsecond line\"}").statusCode(400);
            send(host, messagesPath, "{\"text\":" + marker + "}").statusCode(400);
            send(host, messagesPath, "{\"text\":\"" + marker).statusCode(400);
            send(TestPlayers.createGuest("LogOutside", Rules.KIND_HUMAN), messagesPath, markerBody).statusCode(404);
            given().contentType(ContentType.JSON).body(markerBody).when().post(messagesPath).then().statusCode(401);
            given().cookie(TestPlayers.COOKIE, host).contentType("text/plain").body(marker)
                    .when().post(messagesPath).then().statusCode(415);
            given().cookie(TestPlayers.COOKIE, host).when().get("/rooms/" + id)
                    .then().statusCode(200).body("messages[0].text", equalTo(marker));
            given().when().get("/test-only/boom").then().statusCode(500);
        } finally {
            root.removeHandler(collector);
        }

        // then
        List<String> lines = linesOnceWritten(sentinelsBefore);
        assertThat(lines.stream().filter(line -> line.contains(SENTINEL)).count(), greaterThan(sentinelsBefore));
        assertThat(lines.stream().filter(line -> line.contains(marker)).toList(), empty());
        assertThat(records.stream().filter(record -> record.contains(SENTINEL)).toList(), not(empty()));
        assertThat(records.stream().filter(record -> record.contains(marker)).toList(), empty());
    }

    private int sendChunked(String id, String token, String body) throws IOException, InterruptedException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(roomsUri.resolve("/rooms/" + id + "/messages"))
                .header("Cookie", TestPlayers.COOKIE + "=" + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes)))
                .build();
        int status;
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            status = client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
        }
        return status;
    }

    private static ValidatableResponse send(String token, String path, String body) {
        return given().cookie(TestPlayers.COOKIE, token).contentType(ContentType.JSON).body(body)
                .when().post(path).then();
    }

    // The message, its parameters and the whole exception chain of a record.
    private static String describe(LogRecord record) {
        StringWriter thrown = new StringWriter();
        if (null != record.getThrown()) {
            record.getThrown().printStackTrace(new PrintWriter(thrown));
        }
        return record.getMessage() + " " + Arrays.toString(record.getParameters()) + " " + thrown;
    }

    // The file outlives a run, so the sentinel is counted, not just found.
    private static List<String> linesOnceWritten(long sentinelsBefore) throws IOException, InterruptedException {
        List<String> lines = readLog();
        for (int attempt = 0; sentinelsBefore >= count(lines, SENTINEL) && 50 > attempt; attempt++) {
            Thread.sleep(100);
            lines = readLog();
        }
        return lines;
    }

    private static long count(List<String> lines, String text) {
        return lines.stream().filter(line -> line.contains(text)).count();
    }

    private static List<String> readLog() throws IOException {
        Path log = Path.of(JsonLogProfile.LOG_FILE);
        return Files.exists(log) ? Files.readAllLines(log, StandardCharsets.ISO_8859_1) : List.of();
    }
}
