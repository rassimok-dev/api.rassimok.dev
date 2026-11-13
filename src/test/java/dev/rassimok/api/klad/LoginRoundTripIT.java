package dev.rassimok.api.klad;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@QuarkusIntegrationTest
@WithTestResource(MockMailer.class)
class LoginRoundTripIT extends LoginRoundTripTest {

    // Where the test launcher writes the binary's log. The mock mailbox logs
    // every mail with its text there.
    private static final Path LOG = Path.of("target", "quarkus.log");
    private static final int READ_ATTEMPTS = 50;

    private final Map<String, Integer> mailsRead = new HashMap<>();

    // Waits for the next mail to the address, so a second code is never
    // mistaken for the first while the log is being written.
    @Override
    protected String sentCode(String email) throws IOException, InterruptedException {
        int alreadyRead = mailsRead.getOrDefault(email, 0);
        List<String> texts = mailTexts(email);
        for (int attempt = 1; alreadyRead >= texts.size() && READ_ATTEMPTS > attempt; attempt++) {
            Thread.sleep(100);
            texts = mailTexts(email);
        }
        mailsRead.put(email, alreadyRead + 1);
        return codeIn(alreadyRead < texts.size() ? texts.get(alreadyRead) : null);
    }

    private static List<String> mailTexts(String email) throws IOException {
        Pattern mail = Pattern.compile("to \\[" + Pattern.quote(email) + "\\].*?text body: \\R(.*?)\\Rhtml body:",
                Pattern.DOTALL);
        // Latin-1 decodes any bytes, and only ASCII is matched.
        Matcher matcher = mail.matcher(new String(Files.readAllBytes(LOG), StandardCharsets.ISO_8859_1));
        List<String> texts = new ArrayList<>();
        while (matcher.find()) {
            texts.add(matcher.group(1));
        }
        return texts;
    }
}
