package com.example.kvstore.command;

import com.example.kvstore.KVStore;
import com.example.kvstore.KVStoreOperation;
import com.example.kvstore.KVStoreResult;

import lombok.Getter;

@Getter
public class DeleteCommand implements KVCommand {
    private final String key;
    private final KVStoreOperation operation = KVStoreOperation.DELETE;

    public DeleteCommand(String key) {
        this.key = key;
    }

    @Override
    public KVStoreResult apply(KVStore store) {
        return store.delete(key);
    }

    @Override
    public String serialize() {
        return String.format("%s|%s", operation, key);
    }
}
