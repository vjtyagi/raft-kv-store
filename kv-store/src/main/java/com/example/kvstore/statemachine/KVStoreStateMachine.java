package com.example.kvstore.statemachine;

import com.example.kvstore.KVStore;
import com.example.kvstore.KVStoreResult;
import com.example.kvstore.command.KVCommand;
import com.example.raft.statemachine.*;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class KVStoreStateMachine implements StateMachine {

    private final KVStore kvStore;

    public KVStoreStateMachine(KVStore kvStore) {
        this.kvStore = kvStore;
    }

    @Override
    public boolean apply(StateMachineCommand command) {
        if (!(command instanceof KVCommand)) {
            log.error("Invalid command type: {}", command.getClass());
            return false;
        }
        KVCommand kvCommand = (KVCommand) command;
        try {
            KVStoreResult result = kvCommand.apply(kvStore);
            return result.isSuccess();
        } catch (Exception e) {
            log.error("Error applying command: {}", e.getMessage());
            return false;
        }

    }

    @Override
    public void restoreSnapshot(String arg0) {
        // Todo: implement restore logic
    }

    @Override
    public String takeSnapshot() {
        // TODO implement snapshot logic
        return "";
    }

    public KVStore getKVStore() {
        return kvStore;
    }

    @Override
    public StateMachineCommand deserializeCommand(String serializedCommand) {
        try {
            return KVCommand.deserialize(serializedCommand);
        } catch (Exception e) {
            log.error("Failed to deserialize KV command: {}", serializedCommand, e);
            throw new IllegalArgumentException("Invalid KV command format: " + serializedCommand, e);

        }
    }

}
