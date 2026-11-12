package dev.rassimok.api;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class StatusResourceTest {

    @Test
    void statusReportsService() {
        given()
                .when().get("/status")
                .then()
                .statusCode(200)
                .body("service", equalTo("api.rassimok.dev"))
                .body("version", notNullValue())
                // Guards the native-image trap: a build-time static
                // Instant.now() would make this wildly negative or huge.
                .body("uptimeSeconds", greaterThanOrEqualTo(0));
    }

    @Test
    void responsesCarrySecurityHeaders() {
        for (String path : new String[] {"/status", "/q/health"}) {
            given()
                    .when().get(path)
                    .then()
                    .statusCode(200)
                    .header("X-Content-Type-Options", equalTo("nosniff"))
                    .header("X-Frame-Options", equalTo("DENY"))
                    .header("Content-Security-Policy", equalTo("default-src 'none'; frame-ancestors 'none'"))
                    .header("Referrer-Policy", equalTo("no-referrer"))
                    .header("Strict-Transport-Security", containsString("max-age=63072000"))
                    .header("Cross-Origin-Resource-Policy", equalTo("same-site"));
        }
    }

    @Test
    void statusIsNeverCached() {
        given()
                .when().get("/status")
                .then()
                .statusCode(200)
                .header("Cache-Control", equalTo("no-store"));
    }

    @Test
    void oversizedBodiesAreRefused() {
        given()
                .body("x".repeat(4096))
                .when().post("/status")
                .then()
                .statusCode(413);
    }

    @Test
    void onlyGetIsAllowed() {
        given()
                .when().delete("/status")
                .then()
                .statusCode(405);
    }

    @Test
    void healthEndpointIsUp() {
        given()
                .when().get("/q/health")
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"));
    }
}
