package com.example.kvstore.listener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.kvstore.KVStoreEvent;
import com.example.kvstore.KVStoreListener;

public class LoggingListener implements KVStoreListener {

    private final Logger logger;

    public LoggingListener() {
        this(LoggerFactory.getLogger(LoggingListener.class));
    }

    // constructor with injectable logger for testing
    LoggingListener(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void onEvent(KVStoreEvent event) {
        logger.info("KVStore operation: {}, on key: {}, old value: {}, new value: {}",
                event.getOperation(),
                event.getKey(),
                event.getOldValue(),
                event.getNewValue());

    }

}
