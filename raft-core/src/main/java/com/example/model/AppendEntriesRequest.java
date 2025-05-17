package com.example.model;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@Getter
@NoArgsConstructor
public class AppendEntriesRequest {
    private int term; // leader's current term (used to update stale followers)
    private String leaderId; // for redirecting clients if needed
    private int prevLogIndex; // Index of log entry immediately preceding new one
    private int prevLogTerm; // Term of prevLogIndex entry - used for consistency check
    private List<RaftLogEntry> entries; // New log entries to store(can be empty for heartbeat)
    /**
     * Leader commitIndex is the index of last commited entry for which leader
     * received acknowledgement from the majority
     * helps followers advance their commitIndex
     */
    private int leaderCommit;

    @Override
    public String toString() {
        return "AppendEntriesRequest [term=" + term + ", leaderId=" + leaderId + ", prevLogIndex=" + prevLogIndex
                + ", prevLogTerm=" + prevLogTerm + ", entries=" + entries + ", leaderCommit=" + leaderCommit + "]";
    }

}
