package com.example.timer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

public class HeartBeatTimerImplTest {
    private HeartBeatTimerImpl timer;
    private static final int HEARTBEAT_INTERVAL = 50;

    @BeforeEach
    public void setup() {
        timer = new HeartBeatTimerImpl(HEARTBEAT_INTERVAL);
    }

    @AfterEach
    public void tearDown() {
        timer.shutdown();
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    public void testTimerFiresImmediately() throws InterruptedException {
        // setup
        CountDownLatch latch = new CountDownLatch(1);
        timer.setHeartBeatHandler(latch::countDown);

        // act
        timer.start();

        // Assert - check that it fires immediately ( within a reasonable timeout)
        assertTrue(latch.await(100, TimeUnit.MILLISECONDS), "Heartbeat should fire immediately after starting");
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    public void testTimerFiresAtRegularIntervals() throws InterruptedException {
        AtomicInteger counter = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(3); // wait for3 heartbeats
        timer.setHeartBeatHandler(() -> {
            counter.incrementAndGet();
            latch.countDown();
        });
        timer.start();
        assertTrue(latch.await(HEARTBEAT_INTERVAL * 4, TimeUnit.MILLISECONDS),
                "Heartbeat should fire at regular intervals");
        assertTrue(counter.get() >= 3, "Heartbeat handle should be called atleast 3 times");
    }

    @Test
    public void testStopPreventsHeartbeats() throws InterruptedException {
        AtomicInteger counter = new AtomicInteger();
        timer.setHeartBeatHandler(counter::incrementAndGet);

        timer.start();
        // wait for atleast one heartbeat
        Thread.sleep(HEARTBEAT_INTERVAL + 20);

        // Get initial count and stop the timer
        int initialCount = counter.get();
        assertTrue(initialCount > 0, "At least one heartbeat should occur before stopping");

        timer.stop();

        // Wait enough time for severla more heartbeats
        Thread.sleep(HEARTBEAT_INTERVAL * 3);
        assertEquals(initialCount, counter.get(), "No additional heartbeats shoudl occur after stopping");
    }

    @Test
    public void testStartWithoutHandlerThrowsException() {
        assertThrows(IllegalStateException.class, () -> timer.start(),
                "Starting timer without handler should throw IllegalSTateException");
    }

    @Test
    public void testRestartAfterStop() throws InterruptedException {
        AtomicInteger counter = new AtomicInteger();
        timer.setHeartBeatHandler(counter::incrementAndGet);

        // Act - first run
        timer.start();
        Thread.sleep(HEARTBEAT_INTERVAL * 2);
        timer.stop();

        int initialCount = counter.get();
        assertTrue(initialCount > 0, "Timer should have fired at least once during first run");

        // Wait to make sure it's really stopped
        Thread.sleep(HEARTBEAT_INTERVAL * 2);
        assertEquals(initialCount, counter.get(), "Timer should not fire when stopped");

        // Restart
        timer.start();
        Thread.sleep(HEARTBEAT_INTERVAL * 2);
        assertTrue(counter.get() > initialCount, "Timer should fire additional heartbeats after restarting");

    }

    @Test
    public void testMultipleStopCalls() throws InterruptedException {
        // setup
        AtomicInteger counter = new AtomicInteger();
        timer.setHeartBeatHandler(counter::incrementAndGet);

        // act
        timer.start();
        Thread.sleep(HEARTBEAT_INTERVAL + 20);

        // Multiple stops should not cause issues
        timer.stop();
        timer.stop();
        timer.stop();

        int count = counter.get();
        Thread.sleep(HEARTBEAT_INTERVAL * 2);
        assertEquals(count, counter.get(), "Timer should remain stopped after multiple stop calls");

    }

    @Test
    public void testChangeHandler() throws InterruptedException {
        // setup
        AtomicInteger counter1 = new AtomicInteger();
        AtomicInteger counter2 = new AtomicInteger();

        timer.setHeartBeatHandler(counter1::incrementAndGet);

        // act
        timer.start();
        Thread.sleep(HEARTBEAT_INTERVAL * 2);

        // change handle while running
        timer.stop();
        timer.setHeartBeatHandler(counter2::incrementAndGet);
        timer.start();

        Thread.sleep(HEARTBEAT_INTERVAL * 2);

        assertTrue(counter1.get() > 0, "First handler should have been called");
        assertTrue(counter2.get() > 0, "Second handler should have been called after changing");
    }

}
