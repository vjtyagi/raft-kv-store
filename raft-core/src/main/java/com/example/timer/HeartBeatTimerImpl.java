package com.example.timer;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class HeartBeatTimerImpl implements HeartBeatTimer {
    private final ScheduledExecutorService scheduler;
    private final int heartbeatIntervalMs;
    private Runnable heartbeatHander;
    private ScheduledFuture<?> scheduledTask;

    public HeartBeatTimerImpl(int heartbeatIntervalMs) {
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat-timer");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void start() {
        if (heartbeatHander == null) {
            throw new IllegalStateException("Heartbeat handler not set");
        }
        stop();
        System.out.println("HeartbeatTimer: Starting with interval " + heartbeatIntervalMs + "ms");
        // Schedule heartbeats at fixed rate
        scheduledTask = scheduler.scheduleWithFixedDelay(this::triggerHeartbeat, 0, heartbeatIntervalMs,
                TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        if (scheduledTask != null) {
            System.out.println("HeartbeatTimer: Stopping");
            scheduledTask.cancel(false);
            scheduledTask = null;
        }
    }

    @Override
    public void setHeartBeatHandler(Runnable handler) {
        this.heartbeatHander = handler;
    }

    private void triggerHeartbeat() {
        System.out.println("HeartbeatTimer: Triggering heartbeat");
        Runnable handler;
        synchronized (this) {
            handler = heartbeatHander;
        }
        if (handler != null) {
            handler.run();
        }
    }

    public void shutdown() {
        stop();
        scheduler.shutdown();
    }

}
