package dev.rassimok.api.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rassimok.api.klad.RefusingSmtpServer;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestProfile(JsonLogProfile.class)
@WithTestResource(RefusingSmtpServer.class)
class JsonLogMailFailureTest {

    @Test
    void shouldWriteLineWithoutStackTraceOrAddress_whenRequestCode_givenServerRefusesRecipient() throws Exception {
        // given
        String email = "json-log-" + UUID.randomUUID() + "@example.com";

        // when
        given().contentType(ContentType.JSON).body(Map.of("email", email))
                .when().post("/login/code").then().statusCode(500);

        // then
        String line = lineWith("Login code mail not sent");
        JsonNode entry = new ObjectMapper().readTree(line);
        assertThat(entry.get("severity").asText(), equalTo("ERROR"));
        assertThat(entry.has("stack_trace"), equalTo(false));
        assertThat(line, not(containsString(email)));
    }

    private static String lineWith(String text) throws Exception {
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
