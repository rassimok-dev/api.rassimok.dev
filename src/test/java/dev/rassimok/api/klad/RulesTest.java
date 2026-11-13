package dev.rassimok.api.klad;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.IntStream;

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

    @Test
    void shouldGiveEveryLevelItsRevision_whenCurrentRevision() {
        // when
        List<Integer> revisions = IntStream.rangeClosed(Rules.LEVEL_MIN, Rules.LEVEL_MAX)
                .mapToObj(Rules::currentRevision)
                .toList();

        // then
        assertThat(revisions, equalTo(List.of(1, 1, 1, 1, 1)));
    }

    @ParameterizedTest
    @ValueSource(ints = {Rules.LEVEL_MIN, Rules.LEVEL_MAX})
    void shouldAccept_whenIsCurrentRevision_givenLevelsCurrentRevision(int level) {
        // when
        boolean isCurrentRevision = Rules.isCurrentRevision(level, Rules.currentRevision(level));

        // then
        assertThat(isCurrentRevision, equalTo(true));
    }

    // An empty value is null: a missing revision or level.
    @ParameterizedTest
    @CsvSource({"1, 0", "1, 2", "5, 2", "1, ", ", 1", "0, 1", "6, 1"})
    void shouldReject_whenIsCurrentRevision_givenOtherRevisionOrUnknownLevel(Integer level, Integer revision) {
        // when
        boolean isCurrentRevision = Rules.isCurrentRevision(level, revision);

        // then
        assertThat(isCurrentRevision, equalTo(false));
    }
}
