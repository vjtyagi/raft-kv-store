package com.example.kvstore;

import lombok.Getter;
import lombok.ToString;

@Getter
@ToString
public class KVStoreEvent {
    private final KVStoreOperation operation;
    private final String key;
    private final String oldValue;
    private final String newValue;
    private final long timestamp;

    public KVStoreEvent(
            KVStoreOperation operation,
            String key,
            String oldValue,
            String newValue) {
        this.operation = operation;
        this.key = key;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.timestamp = System.currentTimeMillis();
    }

}
