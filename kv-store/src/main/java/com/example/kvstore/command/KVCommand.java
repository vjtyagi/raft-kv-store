package com.example.kvstore.command;

import com.example.kvstore.KVStore;
import com.example.kvstore.KVStoreOperation;
import com.example.kvstore.KVStoreResult;

import lombok.Getter;
import com.example.raft.statemachine.StateMachineCommand;

/**
 * Base interface for all KV store commands
 * Commands represent operations that can be applied to the KV store
 * and can be serialized for storage in the Raft log
 */
public interface KVCommand extends StateMachineCommand {

    KVStoreResult apply(KVStore store);

    String serialize();

    KVStoreOperation getOperation();

    String getKey();

    /**
     * Deserializes a command from it's string representation
     * Format: OPERATION|KEY|VALUE ( for PUT)
     * Format: OPERATION|KEY (for GET, DELETE, EXISTS)
     */
    static KVCommand deserialize(String serialized) {
        String[] parts = serialized.split("\\|");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Invalid command format");
        }
        try {
            KVStoreOperation operation = KVStoreOperation.valueOf(parts[0]);
            String key = parts[1];
            return switch (operation) {
                case PUT -> {
                    if (parts.length != 3) {
                        throw new IllegalArgumentException("PUT command requires key and value");
                    }
                    // yield is required to return a value in case of using a code block in switch
                    // case
                    yield new PutCommand(key, parts[2]);
                }
                case GET -> new GetCommand(key);
                case DELETE -> new DeleteCommand(key);
                case EXISTS -> new ExistsCommand(key);
            };
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid operation type: " + parts[0]);
        }
    }
}
