package com.example.model;

import com.example.raft.statemachine.StateMachineCommand;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class RaftLogEntry {
    // log entry structure: 1,1,SET X = 10
    private int index;// index of this entry in the log
    private int term; // Term(election_number) when the entry was received by the leader
    private String command; // the actual command to be applied to state machine

    public RaftLogEntry(int index, int term, StateMachineCommand command) {
        this.index = index;
        this.term = term;
        this.command = command.serialize();

    }

    public RaftLogEntry(int index, int term, String command) {
        this.index = index;
        this.term = term;
        this.command = command;
    }

    @Override
    public String toString() {
        return "RaftLogEntry [index=" + index + ", term=" + term + ", command=" + command + "]";
    }

}
