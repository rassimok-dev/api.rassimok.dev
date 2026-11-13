package dev.rassimok.api.klad;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.concurrent.CompletionException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.sameInstance;

class LoginResourceRootCauseTest {

    @Test
    void shouldReturnInnermostCause_whenRootCause_givenWrappedException() {
        // given
        IOException innermost = new IOException("refused");
        CompletionException wrapped = new CompletionException(new IllegalStateException(innermost));

        // when
        Throwable rootCause = LoginResource.rootCause(wrapped);

        // then
        assertThat(rootCause, sameInstance(innermost));
    }

    @Test
    @Timeout(5)
    void shouldStop_whenRootCause_givenCauseCycle() {
        // given
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);

        // when
        Throwable rootCause = LoginResource.rootCause(first);

        // then
        assertThat(rootCause, anyOf(sameInstance(first), sameInstance(second)));
    }
}
