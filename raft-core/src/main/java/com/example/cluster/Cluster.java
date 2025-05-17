package com.example.cluster;

import com.example.model.AppendEntriesRequest;
import com.example.model.AppendEntriesResponse;
import com.example.model.RequestVoteRequest;
import com.example.model.RequestVoteResponse;

public interface Cluster {
    RequestVoteResponse sendRequestVote(String toNodeId, RequestVoteRequest request);

    AppendEntriesResponse sendAppendEntries(String to, AppendEntriesRequest req);
}
