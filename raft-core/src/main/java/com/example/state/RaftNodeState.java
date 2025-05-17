package com.example.state;

import java.util.ArrayList;
import java.util.List;

import com.example.model.RaftLogEntry;

import lombok.Data;

@Data
public class RaftNodeState {
    private int currentTerm = 0;
    private String votedFor = null;
    private RaftState state = RaftState.FOLLOWER;
    private int commitIndex = 0;
    private int lastApplied = 0;
    private List<RaftLogEntry> log = new ArrayList<>();
}
