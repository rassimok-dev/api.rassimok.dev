package dev.rassimok.api.klad;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

class RulesTest {

    @ParameterizedTest
    @ValueSource(strings = {Rules.KIND_HUMAN, Rules.KIND_AGENT})
    void shouldAccept_whenIsKind_givenHumanOrAgent(String kind) {
        // when
        boolean isKind = Rules.isKind(kind);

        // then
        assertThat(isKind, equalTo(true));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"robot", "Human", " agent"})
    void shouldReject_whenIsKind_givenMissingOrUnknownKind(String kind) {
        // when
        boolean isKind = Rules.isKind(kind);

        // then
        assertThat(isKind, equalTo(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {Rules.SHAPE_LANDSCAPE, Rules.SHAPE_PORTRAIT})
    void shouldAccept_whenIsShape_givenKnownShape(String shape) {
        // when
        boolean isShape = Rules.isShape(shape);

        // then
        assertThat(isShape, equalTo(true));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"square", "Portrait"})
    void shouldReject_whenIsShape_givenUnknownShape(String shape) {
        // when
        boolean isShape = Rules.isShape(shape);

        // then
        assertThat(isShape, equalTo(false));
    }
}
