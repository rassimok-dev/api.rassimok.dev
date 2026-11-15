package dev.rassimok.api.logging;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.HashMap;
import java.util.Map;

public class JsonLogProfile implements QuarkusTestProfile {

    public static final String LOG_FILE = "target/json-log-format-test.log";

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(JsonLogSettings.FILE_JSON);
        overrides.put("quarkus.log.file.enable", "true");
        overrides.put("quarkus.log.file.path", LOG_FILE);
        return overrides;
    }
}
