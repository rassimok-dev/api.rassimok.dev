package dev.rassimok.api.klad;

import io.quarkus.test.common.ResourceArg;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

// The binary calls the fake over HTTPS and trusts only its certificate, so
// this proves the outgoing TLS call in the native image.
@QuarkusIntegrationTest
@WithTestResource(value = FakeGoogle.class, initArgs = @ResourceArg(name = FakeGoogle.TLS, value = "true"))
class GoogleLoginRoundTripIT extends GoogleLoginRoundTrip {
}
