package com.example.kvstore.command;

import com.example.kvstore.KVStore;
import com.example.kvstore.KVStoreOperation;
import com.example.kvstore.KVStoreResult;

import lombok.Getter;

@Getter
public class ExistsCommand implements KVCommand {
    private final String key;
    private final KVStoreOperation operation = KVStoreOperation.EXISTS;

    public ExistsCommand(String key) {
        this.key = key;
    }

    @Override
    public KVStoreResult apply(KVStore store) {
        return store.exists(key);
    }

    @Override
    public String serialize() {
        return String.format("%s|%s", operation, key);
    }
}