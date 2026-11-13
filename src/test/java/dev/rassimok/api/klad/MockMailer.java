package dev.rassimok.api.klad;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.Map;

/**
 * The binary under test runs with the prod profile, where mails would go to
 * Resend. This keeps them in the mock mailbox, which logs each one.
 */
public class MockMailer implements QuarkusTestResourceLifecycleManager {

    @Override
    public Map<String, String> start() {
        return Map.of("quarkus.mailer.mock", "true");
    }

    @Override
    public void stop() {
    }
}
