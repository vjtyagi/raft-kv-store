package com.example.raft.statemachine;

public interface StateMachine {
    /**
     * Apply a command to state machine
     * 
     * @param command Command to apply
     * @return true if command was applied successfully
     */
    boolean apply(StateMachineCommand command);

    /**
     * 
     * Take snapshot of current state machine state
     * Used for state transfer and recovery
     * 
     */
    String takeSnapshot();

    /**
     * Restore state machine state from a snapshot
     * 
     * @param snapshot the snapshot data to restore from
     */

    void restoreSnapshot(String snapshot);

    StateMachineCommand deserializeCommand(String serializedCommand);
}
