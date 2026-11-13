package dev.rassimok.api;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class HealthReadinessTest {

    // An inactive datasource also reports this check UP, but without the
    // per-datasource entry, so the entry is what proves a database answered.
    private static final String DEFAULT_DATASOURCE_STATUS =
            "checks.find { it.name == 'Database connections health check' }.data.'<default>'";

    @Test
    void shouldReportDatabaseUp_whenGetReadiness() {
        // when
        ValidatableResponse response = given().when().get("/q/health/ready").then();

        // then
        response.statusCode(200)
                .body("status", equalTo("UP"))
                .body(DEFAULT_DATASOURCE_STATUS, equalTo("UP"));
    }
}
