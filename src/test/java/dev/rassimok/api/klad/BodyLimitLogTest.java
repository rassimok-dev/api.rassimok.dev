package dev.rassimok.api.klad;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;

// JVM only: a native test cannot see the binary's log from here.
@QuarkusTest
class BodyLimitLogTest {

    @TestHTTPResource("/players")
    URI playersUri;

    @Test
    void shouldLogNoWarning_whenCreatePlayer_givenChunkedBodyOverLimit() throws Exception {
        // given
        List<String> warnings = new CopyOnWriteArrayList<>();
        Handler collector = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (Level.WARNING.intValue() <= record.getLevel().intValue()) {
                    warnings.add(record.getLoggerName() + ": " + record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        byte[] body = ("{\"nick\":\"Bob\",\"kind\":\"human\",\"padding\":\"" + "x".repeat(5000) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(playersUri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))
                .build();
        Logger root = Logger.getLogger("");
        root.addHandler(collector);

        // when
        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
            // The server logs after the 413 has gone out, so give it a moment.
            Thread.sleep(1000);
        } finally {
            root.removeHandler(collector);
        }

        // then
        assertThat(response.statusCode(), equalTo(413));
        assertThat(warnings, empty());
    }
}
