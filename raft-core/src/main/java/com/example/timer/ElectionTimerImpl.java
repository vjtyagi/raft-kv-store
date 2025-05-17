package com.example.timer;

import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ElectionTimerImpl implements ElectionTimer {

    private final ScheduledExecutorService scheduler;
    private final int baseTimeoutMs;
    private final int timeoutVarianceMs;
    private final Random random = new Random();
    private final AtomicBoolean running = new AtomicBoolean();
    private Runnable timeoutHander;
    private ScheduledFuture<?> scheduledTask;

    public ElectionTimerImpl(int baseTimeoutMs, int timeoutVarianceMs) {
        this.baseTimeoutMs = baseTimeoutMs;
        this.timeoutVarianceMs = timeoutVarianceMs;
        log.info("ElectionTimer created with base: {}ms, variance: {}ms", baseTimeoutMs, timeoutVarianceMs);

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "election-timer");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void start() {
        if (timeoutHander == null) {
            throw new IllegalStateException("Timeout handler not set");
        }
        stop();
        int timeout = calculateRandomTimeout();
        // System.out.println("ElectionTimer: Starting with timeout " + timeout + "ms");
        scheduledTask = scheduler.schedule(this::handleTimeout, timeout, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        synchronized (this) {
            if (scheduledTask != null) {
                scheduledTask.cancel(true);
                scheduledTask = null;
            }
        }

    }

    @Override
    public synchronized void reset() {
        stop();
        start();
    }

    @Override
    public void setTimeoutHandler(Runnable handler) {
        this.timeoutHander = handler;
    }

    private int calculateRandomTimeout() {
        int variance = random.nextInt(2 * timeoutVarianceMs) - timeoutVarianceMs;
        return baseTimeoutMs + variance;
    }

    /**
     * Handle timeout event by calling timeout handler
     */
    private void handleTimeout() {
        System.out.println("handle election timeout called");
        Runnable handler;
        synchronized (this) {
            scheduledTask = null;
            handler = timeoutHander;
        }
        if (handler != null) {
            handler.run();
        }
    }

    /**
     * shutdown timer and cleanup resources
     */
    public void shutdown() {
        stop();
        scheduler.shutdown();
    }

}
