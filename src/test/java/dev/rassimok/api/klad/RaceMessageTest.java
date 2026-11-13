package dev.rassimok.api.klad;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

class RaceMessageTest {

    @Test
    void shouldReadPing_whenParse_givenPing() {
        // when
        RaceMessage message = RaceMessage.parse("{\"type\":\"ping\"}");

        // then
        assertThat(message, equalTo(new RaceMessage.Ping()));
    }

    @Test
    void shouldReadPosition_whenParse_givenFractionsAndTick() {
        // when
        RaceMessage message = RaceMessage.parse("{\"type\":\"pos\",\"x\":3.25,\"y\":-1,\"tick\":120}");

        // then
        assertThat(message, equalTo(new RaceMessage.Position(3.25, -1, 120)));
    }

    @Test
    void shouldReadPosition_whenParse_givenTickBeyondInteger() {
        // when
        RaceMessage message = RaceMessage.parse("{\"type\":\"pos\",\"x\":0,\"y\":0,\"tick\":5000000000}");

        // then
        assertThat(message, equalTo(new RaceMessage.Position(0, 0, 5_000_000_000L)));
    }

    @Test
    void shouldReadFinish_whenParse_givenTicks() {
        // when
        RaceMessage message = RaceMessage.parse("{\"type\":\"finish\",\"ticks\":7200}");

        // then
        assertThat(message, equalTo(new RaceMessage.Finish(7200)));
    }

    @Test
    void shouldReadPing_whenParse_givenExtraFields() {
        // when
        RaceMessage message = RaceMessage.parse("{\"type\":\"ping\",\"pad\":\"xxxx\"}");

        // then
        assertThat(message, equalTo(new RaceMessage.Ping()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "hello", "{", "{\"type\":\"ping\"}x", "5", "\"ping\"", "true", "null", "[]",
            "[{\"type\":\"ping\"}]", "{}", "{\"type\":null}", "{\"type\":5}", "{\"type\":\"PING\"}",
            "{\"type\":\"pong\"}", "{\"type\":\"start\"}", "{\"type\":{\"type\":\"ping\"}}",
            "{\"type\":\"pos\"}", "{\"type\":\"pos\",\"x\":1,\"y\":1}", "{\"type\":\"pos\",\"x\":\"1\",\"y\":1,\"tick\":1}",
            "{\"type\":\"pos\",\"x\":1,\"y\":null,\"tick\":1}", "{\"type\":\"pos\",\"x\":1e999,\"y\":1,\"tick\":1}",
            "{\"type\":\"pos\",\"x\":1,\"y\":-1e999,\"tick\":1}", "{\"type\":\"pos\",\"x\":NaN,\"y\":1,\"tick\":1}",
            "{\"type\":\"pos\",\"x\":1,\"y\":1,\"tick\":-1}", "{\"type\":\"pos\",\"x\":1,\"y\":1,\"tick\":1.5}",
            "{\"type\":\"pos\",\"x\":1,\"y\":1,\"tick\":5.0}", "{\"type\":\"pos\",\"x\":1,\"y\":1,\"tick\":\"1\"}",
            "{\"type\":\"pos\",\"x\":1,\"y\":1,\"tick\":10000000000000000000}", "{\"type\":\"finish\"}",
            "{\"type\":\"finish\",\"ticks\":-5}", "{\"type\":\"finish\",\"ticks\":600.5}",
            "{\"type\":\"finish\",\"ticks\":true}", "{\"type\":\"finish\",\"tick\":600}"})
    void shouldReadBadMessage_whenParse_givenMalformedOrUnknownMessage(String text) {
        // when
        RaceMessage message = RaceMessage.parse(text);

        // then
        assertThat(message, equalTo(new RaceMessage.Bad()));
    }
}
