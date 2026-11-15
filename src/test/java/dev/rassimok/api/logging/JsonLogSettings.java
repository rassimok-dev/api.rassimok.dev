package dev.rassimok.api.logging;

import java.util.Map;

/**
 * The tests cannot read the console of the process under test, so the JSON
 * settings of application.properties are applied to the log file instead.
 */
final class JsonLogSettings {

    static final Map<String, String> FILE_JSON = Map.of(
            "quarkus.log.file.json.enabled", "true",
            "quarkus.log.file.json.key-overrides", "level=severity,stack_trace=stack_trace",
            "quarkus.log.file.json.excluded-keys",
            "sequence,loggerClassName,threadId,mdc,ndc,hostName,processName,processId",
            "quarkus.log.file.json.exception-output-type", "formatted");

    private JsonLogSettings() {
    }
}
