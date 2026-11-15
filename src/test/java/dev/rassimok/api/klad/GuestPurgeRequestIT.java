package dev.rassimok.api.klad;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

// The profile is not inherited; without it the binary of the other tests,
// whose day's purge has already run, would answer.
@QuarkusIntegrationTest
@TestProfile(GuestPurgeRequestTest.FreshPurge.class)
class GuestPurgeRequestIT extends GuestPurgeRequestTest {

    // Where the test launcher writes the binary's log.
    private static final Path LOG = Path.of("target", "quarkus.log");

    @Override
    protected List<String> loggedLines() throws Exception {
        return Files.readAllLines(LOG, StandardCharsets.ISO_8859_1);
    }
}
