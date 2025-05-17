package com.example.timer;

public interface HeartBeatTimer {
    /**
     * Start sending periodic heartbeats
     */
    void start();

    /**
     * Stop sending periodic heartbeats
     */
    void stop();

    /**
     * Set the handler to be called for each heartbeat
     */
    void setHeartBeatHandler(Runnable handler);
}
