package dev.rassimok.api.klad;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

class ClearRequestTest {

    static Stream<ClearRequest> validClears() {
        return Stream.of(
                new ClearRequest(1, Rules.SHAPE_LANDSCAPE, 1, 600, 0, 1),
                new ClearRequest(5, Rules.SHAPE_PORTRAIT, 1000, 432_000, 100, 100),
                new ClearRequest(3, Rules.SHAPE_PORTRAIT, 2, 1500, 4, 7));
    }

    static Stream<ClearRequest> invalidClears() {
        return Stream.of(
                new ClearRequest(0, Rules.SHAPE_LANDSCAPE, 1, 600, 0, 1),
                new ClearRequest(6, Rules.SHAPE_LANDSCAPE, 1, 600, 0, 1),
                new ClearRequest(null, Rules.SHAPE_LANDSCAPE, 1, 600, 0, 1),
                new ClearRequest(1, "square", 1, 600, 0, 1),
                new ClearRequest(1, null, 1, 600, 0, 1),
                new ClearRequest(1, Rules.SHAPE_LANDSCAPE, 0, 600, 0, 1),
                new ClearRequest(1, Rules.SHAPE_LANDSCAPE, 1001, 600, 0, 1),
                new ClearRequest(1, Rules.SHAPE_LANDSCAPE, null, 600, 0, 1),
                new ClearRequest(1, Rules.SHAPE_LANDSCAPE, 1, 599, 0, 1),
                new ClearRequest(1, Rules.SHAPE_LANDSCAPE, 1, 432_001, 0, 1),
                new ClearRequest(1, Rules.SHAPE_LANDSCAPE, 1, null, 0, 1),
                new ClearRequest(1, Rules.SHAPE_LANDSCAPE, 1, 600, -1, 1),
                new ClearRequest(1, Rules.SHAPE_LANDSCAPE, 1, 600, 6, 5),
                new ClearRequest(1, Rules.SHAPE_LANDSCAPE, 1, 600, null, 1),
                new ClearRequest(1, Rules.SHAPE_LANDSCAPE, 1, 600, 0, 0),
                new ClearRequest(1, Rules.SHAPE_LANDSCAPE, 1, 600, 0, 101),
                new ClearRequest(1, Rules.SHAPE_LANDSCAPE, 1, 600, 0, null));
    }

    @ParameterizedTest
    @MethodSource("validClears")
    void shouldAccept_whenIsValid_givenValuesWithinBounds(ClearRequest clear) {
        // when
        boolean isValid = clear.isValid();

        // then
        assertThat(isValid, equalTo(true));
    }

    @ParameterizedTest
    @MethodSource("invalidClears")
    void shouldReject_whenIsValid_givenValueMissingOrOutOfBounds(ClearRequest clear) {
        // when
        boolean isValid = clear.isValid();

        // then
        assertThat(isValid, equalTo(false));
    }
}
