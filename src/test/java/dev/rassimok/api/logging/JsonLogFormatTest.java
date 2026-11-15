package dev.rassimok.api.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(JsonLogProfile.class)
class JsonLogFormatTest {

    @Test
    void shouldWriteOneJsonObject_whenUnexpectedError_givenJsonLogsOn() throws Exception {
        // given
        String path = "/test-only/boom";

        // when
        given().when().get(path).then().statusCode(500);

        // then
        JsonNode entry = new ObjectMapper().readTree(lineWith("Unexpected error"));
        assertThat(entry.get("severity").asText(), equalTo("ERROR"));
        assertThat(entry.get("message").asText(), equalTo("Unexpected error"));
        assertThat(entry.get("stack_trace").asText(),
                containsString("java.lang.IllegalStateException: boom for the log format test\n\tat "));
        assertThat(entry.has("level"), equalTo(false));
        assertThat(entry.has("stackTrace"), equalTo(false));
    }

    private static String lineWith(String text) throws IOException, InterruptedException {
        Path log = Path.of(JsonLogProfile.LOG_FILE);
        String found = null;
        for (int attempt = 0; null == found && 50 > attempt; attempt++) {
            found = Files.readAllLines(log).stream().filter(line -> line.contains(text)).findFirst().orElse(null);
            if (null == found) {
                Thread.sleep(100);
            }
        }
        return found;
    }
}
