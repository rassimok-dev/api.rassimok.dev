package dev.rassimok.api.klad;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

class GuestPurgeTest {

    private static final Instant START = Instant.parse("2026-11-15T10:00:00Z");
    private static final int CALLERS = 32;

    @Test
    void shouldClaimRun_whenTryClaimRun_givenNoRunYet() {
        // given
        GuestPurge purge = new GuestPurge(new FakeClock(START));

        // when
        boolean isClaimed = purge.tryClaimRun();

        // then
        assertThat(isClaimed, equalTo(true));
    }

    @Test
    void shouldRefuseRun_whenTryClaimRun_givenRunLessThanADayAgo() {
        // given
        FakeClock clock = new FakeClock(START);
        GuestPurge purge = new GuestPurge(clock);
        purge.tryClaimRun();
        clock.advance(Rules.PURGE_INTERVAL.minus(Duration.ofSeconds(1)));

        // when
        boolean isClaimed = purge.tryClaimRun();

        // then
        assertThat(isClaimed, equalTo(false));
    }

    @Test
    void shouldClaimRun_whenTryClaimRun_givenRunADayAgo() {
        // given
        FakeClock clock = new FakeClock(START);
        GuestPurge purge = new GuestPurge(clock);
        purge.tryClaimRun();
        clock.advance(Rules.PURGE_INTERVAL);

        // when
        boolean isClaimed = purge.tryClaimRun();

        // then
        assertThat(isClaimed, equalTo(true));
    }

    @Test
    void shouldCountDayFromLastRun_whenTryClaimRun_givenRunLateInTheDay() {
        // given
        FakeClock clock = new FakeClock(START);
        GuestPurge purge = new GuestPurge(clock);
        purge.tryClaimRun();
        clock.advance(Duration.ofDays(3));
        purge.tryClaimRun();
        clock.advance(Rules.PURGE_INTERVAL.minus(Duration.ofSeconds(1)));

        // when
        boolean isClaimed = purge.tryClaimRun();

        // then
        assertThat(isClaimed, equalTo(false));
    }

    @Test
    void shouldClaimOnce_whenTryClaimRun_givenParallelCallers() throws Exception {
        // given
        GuestPurge purge = new GuestPurge(new FakeClock(START));
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> claims = new ArrayList<>();

        // when
        try (ExecutorService callers = Executors.newFixedThreadPool(CALLERS)) {
            for (int i = 0; i < CALLERS; i++) {
                claims.add(callers.submit(() -> {
                    start.await();
                    return purge.tryClaimRun();
                }));
            }
            start.countDown();
        }

        // then
        long claimed = 0;
        for (Future<Boolean> claim : claims) {
            claimed += claim.get() ? 1 : 0;
        }
        assertThat(claimed, equalTo(1L));
    }
}
