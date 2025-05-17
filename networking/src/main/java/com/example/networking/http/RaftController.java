package com.example.networking.http;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.model.AppendEntriesRequest;
import com.example.model.AppendEntriesResponse;
import com.example.model.RequestVoteRequest;
import com.example.model.RequestVoteResponse;
import com.example.networking.rpc.HttpRaftRpcService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/raft")
@RequiredArgsConstructor
@Slf4j
public class RaftController {
    private final HttpRaftRpcService raftRpcService;

    @PostMapping("/append-entries")
    public AppendEntriesResponse appendEntries(@RequestBody AppendEntriesRequest request) {
        log.debug("Recieved append entries request: {}", request);
        AppendEntriesResponse response = raftRpcService.handleAppendEntries(request);
        log.debug("Sending AppendEntries response: {}", response);
        return response;
    }

    @PostMapping("/request-vote")
    public RequestVoteResponse requestVote(@RequestBody RequestVoteRequest request) {
        log.debug("Received RequestVote request: {}", request);
        RequestVoteResponse response = raftRpcService.handleRequestVote(request);
        log.debug("Sending RequestVote response: {}", response);
        return response;
    }

}
