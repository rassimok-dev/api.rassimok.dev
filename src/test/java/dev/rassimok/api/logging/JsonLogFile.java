package dev.rassimok.api.logging;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.Map;

public class JsonLogFile implements QuarkusTestResourceLifecycleManager {

    @Override
    public Map<String, String> start() {
        return JsonLogSettings.FILE_JSON;
    }

    @Override
    public void stop() {
    }
}
