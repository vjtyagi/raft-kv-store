package com.example.timer;

public interface ElectionTimer {
    /**
     * start the election timer
     * when timer expires, timeout handler will be called
     */
    void start();

    /**
     * stop the election timer
     * any scheduled timeout will be cancelled
     */
    void stop();

    /**
     * Resets the election timer with a new random timeout
     * Any existing timeout will be cancelled
     */
    void reset();

    /**
     * Sets the handler to be called with election timeout occurrs
     */
    void setTimeoutHandler(Runnable handler);

    void shutdown();

}
