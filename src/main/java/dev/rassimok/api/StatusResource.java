package dev.rassimok.api;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;

@Path("/status")
@ApplicationScoped
public class StatusResource {

    /*
     * Deliberately NOT a static field initialised with Instant.now().
     *
     * GraalVM runs static initialisers at *build* time, so a static
     * `Instant.now()` would freeze the moment the image was compiled in CI.
     * Uptime would then be measured from the build, not from the process
     * start, and would be wildly wrong in production while looking perfectly
     * correct in JVM dev mode. Observing StartupEvent keeps it at runtime.
     */
    private Instant started;

    void onStart(@Observes StartupEvent event) {
        started = Instant.now();
    }

    @ConfigProperty(name = "quarkus.application.version")
    String version;

    public record Status(String service, String version, long uptimeSeconds, String time) {
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Status status() {
        return new Status(
                "api.rassimok.dev",
                version,
                Duration.between(started, Instant.now()).toSeconds(),
                Instant.now().toString());
    }
}
