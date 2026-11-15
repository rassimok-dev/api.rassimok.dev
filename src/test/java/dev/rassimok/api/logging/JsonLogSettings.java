package dev.rassimok.api.logging;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * The tests cannot read the console of the process under test, so the JSON
 * console settings of application.properties are copied onto the log file.
 * A broken console key then breaks the tests.
 */
final class JsonLogSettings {

    // Read from the source tree: the test runtime serves an empty
    // application.properties at the classpath root.
    private static final Path APPLICATION_PROPERTIES = Path.of("src", "main", "resources", "application.properties");
    private static final String CONSOLE_PREFIX = "quarkus.log.console.json.";
    private static final String FILE_PREFIX = "quarkus.log.file.json.";
    private static final String PRODUCTION_ENABLED_KEY = "%prod." + CONSOLE_PREFIX + "enabled";

    static final Map<String, String> FILE_JSON = fileJsonFromApplicationProperties();

    private JsonLogSettings() {
    }

    private static Map<String, String> fileJsonFromApplicationProperties() {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(APPLICATION_PROPERTIES)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, String> settings = new HashMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith(CONSOLE_PREFIX) && !"enabled".equals(key.substring(CONSOLE_PREFIX.length()))) {
                settings.put(FILE_PREFIX + key.substring(CONSOLE_PREFIX.length()), properties.getProperty(key));
            }
        }
        settings.put(FILE_PREFIX + "enabled", properties.getProperty(PRODUCTION_ENABLED_KEY));
        if (!"true".equals(settings.get(FILE_PREFIX + "enabled")) || 4 > settings.size()) {
            throw new IllegalStateException("application.properties lacks the production console JSON settings: " + settings);
        }
        return Map.copyOf(settings);
    }
}
