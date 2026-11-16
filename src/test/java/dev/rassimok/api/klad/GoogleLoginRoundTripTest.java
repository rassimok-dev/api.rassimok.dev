package dev.rassimok.api.klad;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@WithTestResource(FakeGoogle.class)
class GoogleLoginRoundTripTest extends GoogleLoginRoundTrip {
}
