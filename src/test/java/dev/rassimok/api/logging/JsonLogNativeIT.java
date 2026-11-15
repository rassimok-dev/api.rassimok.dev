package dev.rassimok.api.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

// The launcher writes the binary's log file to target/quarkus.log; the
// console itself cannot be read from the test.
@QuarkusIntegrationTest
@WithTestResource(JsonLogFile.class)
class JsonLogNativeIT {

    private static final Path LOG = Path.of("target", "quarkus.log");

    @Test
    void shouldWriteJsonWithSeverity_whenStart_givenProdProfile() throws Exception {
        // given
        String startLine = null;

        // when
        for (int attempt = 0; null == startLine && 50 > attempt; attempt++) {
            startLine = Files.readAllLines(LOG, StandardCharsets.ISO_8859_1).stream()
                    .filter(line -> line.contains("Installed features")).findFirst().orElse(null);
            if (null == startLine) {
                Thread.sleep(100);
            }
        }

        // then
        assertThat(startLine, notNullValue());
        JsonNode entry = new ObjectMapper().readTree(startLine);
        assertThat(entry.get("severity").asText(), equalTo("INFO"));
        assertThat(entry.has("level"), equalTo(false));
    }
}
