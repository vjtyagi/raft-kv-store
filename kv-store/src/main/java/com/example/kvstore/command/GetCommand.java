package com.example.kvstore.command;

import com.example.kvstore.KVStore;
import com.example.kvstore.KVStoreOperation;
import com.example.kvstore.KVStoreResult;

import lombok.Getter;

@Getter
public class GetCommand implements KVCommand {
    private final String key;
    private final KVStoreOperation operation = KVStoreOperation.GET;

    public GetCommand(String key) {
        this.key = key;
    }

    public KVStoreResult apply(KVStore store) {
        return store.get(key);
    }

    public String serialize() {
        return String.format("%s|%s", operation, key);
    }
}
