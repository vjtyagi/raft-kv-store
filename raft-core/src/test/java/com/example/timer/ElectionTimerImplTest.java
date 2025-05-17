package com.example.timer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

public class ElectionTimerImplTest {
    private ElectionTimer timer;
    private static final int BASE_TIMEOUT = 50;
    private static final int TIMEOUT_VARIANCE = 50;

    @BeforeEach
    public void setup() {
        timer = new ElectionTimerImpl(TIMEOUT_VARIANCE, BASE_TIMEOUT);
    }

    @AfterEach
    public void tearDown() {
        timer.shutdown();
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testTimerFiresTimeout() throws InterruptedException {
        // setup
        CountDownLatch latch = new CountDownLatch(1);
        timer.setTimeoutHandler(latch::countDown);

        // Act
        timer.start();

        assertTrue(latch.await(BASE_TIMEOUT + TIMEOUT_VARIANCE + 50, TimeUnit.MILLISECONDS),
                "Timer should fire within the expected timeframe");
    }

    @Test
    public void testResetRestartsClock() throws InterruptedException {
        // setup
        AtomicBoolean handlerCalled = new AtomicBoolean();
        timer.setTimeoutHandler(() -> handlerCalled.set(true));

        // act
        timer.start();
        // Sleep for a short perod but not long enough for timer to fire
        Thread.sleep(BASE_TIMEOUT / 2);
        // reset the timer
        timer.reset();
        // Sleep again for a short period
        Thread.sleep(BASE_TIMEOUT / 2);

        // Assert
        assertFalse(handlerCalled.get(), "Handler should not be called when timer is reset");

        // wait to ensure timer does fire after reset
        Thread.sleep(BASE_TIMEOUT + TIMEOUT_VARIANCE);
        assertTrue(handlerCalled.get(), "Handler should be called after the full timeout period");
    }

    @Test
    public void testStopPreventsTimeout() throws InterruptedException {
        // setup
        AtomicBoolean handlerCalled = new AtomicBoolean();
        timer.setTimeoutHandler(() -> handlerCalled.set(true));

        // Act
        timer.start();
        timer.stop();
        Thread.sleep(BASE_TIMEOUT * 2 + TIMEOUT_VARIANCE);
        assertFalse(handlerCalled.get(), "Handler should not be called when timer is stopped");
    }

    @Test
    public void testStartWithoutHandlerThrowsException() {
        assertThrows(IllegalStateException.class, () -> timer.start(),
                "Starting timer without a handler should throw IllegalStateException");
    }

    @Test
    public void testMultipleResets() throws InterruptedException {
        // setup
        CountDownLatch latch = new CountDownLatch(1);
        timer.setTimeoutHandler(latch::countDown);

        // act
        timer.start();

        // Multiple resets
        for (int i = 0; i < 5; i++) {
            Thread.sleep(BASE_TIMEOUT / 4);
            timer.reset();
        }
        // Assert
        // boolean await(timeout,unit) : returns true if countdown reaches 0 before
        // timeout, false otherwise
        assertTrue(latch.await(BASE_TIMEOUT + TIMEOUT_VARIANCE + 50, TimeUnit.MILLISECONDS),
                "Timer should eventually fire even after multiple resets");

    }

    @Test
    public void testMultipleStopsAndStarts() throws InterruptedException {
        // setup
        AtomicBoolean handlerCalled = new AtomicBoolean(false);
        timer.setTimeoutHandler(() -> handlerCalled.set(true));

        // act
        timer.start();
        timer.stop();
        Thread.sleep(BASE_TIMEOUT + 10);
        assertFalse(handlerCalled.get(), "Handler should not be called when timer is stopped");

        // start again
        timer.start();
        Thread.sleep(BASE_TIMEOUT + TIMEOUT_VARIANCE + 50);
        assertTrue(handlerCalled.get(), "Hanlder should be called after restarting the timer");
    }

    @Test
    public void testConcurrentResets() throws InterruptedException {
        // setup
        AtomicBoolean handlerCalled = new AtomicBoolean(false);
        timer.setTimeoutHandler(() -> handlerCalled.set(true));

        // act
        timer.start();
        // Simulate concurrent resets from multiple threads
        Thread[] threads = new Thread[5];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                try {
                    Thread.sleep(10); // delay to increase chance of concurrency
                    timer.reset();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) {
            t.join();
        }
        // Assert
        Thread.sleep(BASE_TIMEOUT + TIMEOUT_VARIANCE + 50);
        assertTrue(handlerCalled.get(), "Handler should be called after concurrent resets");

    }
}
