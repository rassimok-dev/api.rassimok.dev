package dev.rassimok.api.logging;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/test-only/boom")
public class ThrowingResource {

    @GET
    public String boom() {
        throw new IllegalStateException("boom for the log format test");
    }
}
