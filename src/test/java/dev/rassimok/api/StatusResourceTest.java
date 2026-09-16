package dev.rassimok.api;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
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
    void healthEndpointIsUp() {
        given()
                .when().get("/q/health")
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"));
    }
}
