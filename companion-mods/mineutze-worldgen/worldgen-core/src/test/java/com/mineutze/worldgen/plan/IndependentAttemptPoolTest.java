package com.mineutze.worldgen.plan;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndependentAttemptPoolTest {

    private static final Duration GENEROUS_WAIT = Duration.ofSeconds(10);

    @Test
    void returnsEverySubmittedResult() {
        try (IndependentAttemptPool<Integer> pool = new IndependentAttemptPool<>(3)) {
            for (int value = 0; value < 5; value++) {
                int submitted = value;
                pool.submit(() -> submitted);
            }

            Set<Integer> received = new HashSet<>();
            for (int taken = 0; taken < 5; taken++) {
                received.add(pool.takeCompleted(GENEROUS_WAIT).orElseThrow());
            }

            assertEquals(Set.of(0, 1, 2, 3, 4), received);
        }
    }

    /** Two attempts that each wait for the other can only both finish if the pool really runs them at the same time. */
    @Test
    void runsAttemptsConcurrentlyUpToItsThreadCount() {
        CountDownLatch bothStarted = new CountDownLatch(2);
        try (IndependentAttemptPool<Boolean> pool = new IndependentAttemptPool<>(2)) {
            for (int attempt = 0; attempt < 2; attempt++) {
                pool.submit(() -> {
                    bothStarted.countDown();
                    return bothStarted.await(5, TimeUnit.SECONDS);
                });
            }

            assertTrue(pool.takeCompleted(GENEROUS_WAIT).orElseThrow());
            assertTrue(pool.takeCompleted(GENEROUS_WAIT).orElseThrow());
        }
    }

    @Test
    void reportsNothingWhenNoAttemptFinishesInTime() {
        CountDownLatch never = new CountDownLatch(1);
        try (IndependentAttemptPool<Boolean> pool = new IndependentAttemptPool<>(1)) {
            pool.submit(() -> never.await(5, TimeUnit.SECONDS));

            assertEquals(Optional.empty(), pool.takeCompleted(Duration.ofMillis(50)));
            never.countDown();
        }
    }

    @Test
    void aFailedAttemptFailsLoudWithItsCause() {
        try (IndependentAttemptPool<Integer> pool = new IndependentAttemptPool<>(1)) {
            pool.submit(() -> {
                throw new IllegalStateException("attempt exploded");
            });

            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> pool.takeCompleted(GENEROUS_WAIT));
            assertTrue(failure.getMessage().contains("attempt exploded"), failure.getMessage());
        }
    }

    @Test
    void takingWithNothingRunningIsAnError() {
        try (IndependentAttemptPool<Integer> pool = new IndependentAttemptPool<>(1)) {
            assertThrows(IllegalStateException.class, () -> pool.takeCompleted(GENEROUS_WAIT));
        }
    }

    @Test
    void tracksHowManyAttemptsAreStillRunning() {
        try (IndependentAttemptPool<Integer> pool = new IndependentAttemptPool<>(2)) {
            pool.submit(() -> 1);
            pool.submit(() -> 2);
            pool.takeCompleted(GENEROUS_WAIT).orElseThrow();

            assertEquals(1, pool.running());
        }
    }

    @Test
    void rejectsNoThreads() {
        assertThrows(IllegalArgumentException.class, () -> new IndependentAttemptPool<Integer>(0));
    }
}
