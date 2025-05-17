package com.example.kvstore.command;

import com.example.kvstore.KVStore;
import com.example.kvstore.KVStoreOperation;
import com.example.kvstore.KVStoreResult;

import lombok.Getter;

@Getter
public class PutCommand implements KVCommand {

    private final String key;
    private final String value;
    private final KVStoreOperation operation = KVStoreOperation.PUT;

    public PutCommand(String key, String value) {
        this.key = key;
        this.value = value;
    }

    @Override
    public KVStoreResult apply(KVStore store) {
        return store.put(key, value);
    }

    @Override
    public String serialize() {
        return String.format("%s|%s|%s", operation, key, value);
    }

}
