package dev.rassimok.api.klad;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

class ClearRequestTest {

    static Stream<ClearRequest> validClears() {
        return Stream.of(
                new ClearRequest(1, Rules.SHAPE_LANDSCAPE, Rules.currentRevision(1), 600, 0, 1),
                new ClearRequest(5, Rules.SHAPE_PORTRAIT, Rules.currentRevision(5), 432_000, 100, 100),
                new ClearRequest(3, Rules.SHAPE_PORTRAIT, Rules.currentRevision(3), 1500, 4, 7));
    }

    static Stream<ClearRequest> invalidClears() {
        int revision = Rules.currentRevision(1);
        return Stream.of(
                new ClearRequest(0, Rules.SHAPE_LANDSCAPE, revision, 600, 0, 1),
                new ClearRequest(6, Rules.SHAPE_LANDSCAPE, revision, 600, 0, 1),
                new ClearRequest(null, Rules.SHAPE_LANDSCAPE, revision, 600, 0, 1),
                new ClearRequest(1, "square", revision, 600, 0, 1),
                new ClearRequest(1, null, revision, 600, 0, 1),
                new ClearRequest(1, Rules.SHAPE_LANDSCAPE, 0, 600, 0, 1),
                new ClearRequest(1, Rules.SHAPE_LANDSCAPE, revision + 1, 600, 0, 1),
                new ClearRequest(5, Rules.SHAPE_PORTRAIT, Rules.currentRevision(5) + 1, 600, 0, 1),
                new ClearRequest(1, Rules.SHAPE_LANDSCAPE, null, 600, 0, 1),
                new ClearRequest(1, Rules.SHAPE_LANDSCAPE, revision, 599, 0, 1),
                new ClearRequest(1, Rules.SHAPE_LANDSCAPE, revision, 432_001, 0, 1),
                new ClearRequest(1, Rules.SHAPE_LANDSCAPE, revision, null, 0, 1),
                new ClearRequest(1, Rules.SHAPE_LANDSCAPE, revision, 600, -1, 1),
                new ClearRequest(1, Rules.SHAPE_LANDSCAPE, revision, 600, 6, 5),
                new ClearRequest(1, Rules.SHAPE_LANDSCAPE, revision, 600, null, 1),
                new ClearRequest(1, Rules.SHAPE_LANDSCAPE, revision, 600, 0, 0),
                new ClearRequest(1, Rules.SHAPE_LANDSCAPE, revision, 600, 0, 101),
                new ClearRequest(1, Rules.SHAPE_LANDSCAPE, revision, 600, 0, null));
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
