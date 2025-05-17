package com.example.raft.statemachine;

public interface StateMachineCommand {
    /**
     * Serialize command to string for storage in Raft Log
     * Format should be consistent across nodes
     */
    String serialize();

    /**
     * Deserialize command from string
     * EAch implementing class should provide own implementation
     */
    static StateMachineCommand deserialize(String command) {
        throw new UnsupportedOperationException("EAch command type must implement own deserialize");
    }

}
