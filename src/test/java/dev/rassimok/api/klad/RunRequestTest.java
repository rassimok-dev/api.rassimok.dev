package dev.rassimok.api.klad;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

class RunRequestTest {

    static Stream<RunRequest> validRuns() {
        return Stream.of(
                new RunRequest(0, 0, 0),
                new RunRequest(1, 120, 600),
                new RunRequest(5, 10_000_000, 3000),
                new RunRequest(5, 0, 2_160_000));
    }

    static Stream<RunRequest> invalidRuns() {
        return Stream.of(
                new RunRequest(-1, 0, 0),
                new RunRequest(6, 0, 3600),
                new RunRequest(null, 0, 0),
                new RunRequest(0, -1, 0),
                new RunRequest(0, 10_000_001, 0),
                new RunRequest(0, null, 0),
                new RunRequest(0, 0, -1),
                new RunRequest(0, 0, 2_160_001),
                new RunRequest(0, 0, null),
                new RunRequest(1, 0, 599),
                new RunRequest(5, 0, 2999));
    }

    @ParameterizedTest
    @MethodSource("validRuns")
    void shouldAccept_whenIsValid_givenValuesWithinBounds(RunRequest run) {
        // when
        boolean isValid = run.isValid();

        // then
        assertThat(isValid, equalTo(true));
    }

    @ParameterizedTest
    @MethodSource("invalidRuns")
    void shouldReject_whenIsValid_givenValueMissingOrOutOfBounds(RunRequest run) {
        // when
        boolean isValid = run.isValid();

        // then
        assertThat(isValid, equalTo(false));
    }
}
