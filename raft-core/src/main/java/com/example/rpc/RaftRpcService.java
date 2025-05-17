package com.example.rpc;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.example.model.AppendEntriesRequest;
import com.example.model.AppendEntriesResponse;
import com.example.model.RequestVoteRequest;
import com.example.model.RequestVoteResponse;

public interface RaftRpcService {
    /**
     * Sends a RequestVote RPC to specified node
     * 
     * @param targetNodeId of the target node
     * @param RequestVote  request to send
     * @return CompletableFuture that will complete with response or an exception
     */
    CompletableFuture<RequestVoteResponse> sendRequestVote(String targetNodeId, RequestVoteRequest request);

    /**
     * Sends an AppendEntries RPC to the specified node
     * 
     * @param targetNodeId of the target node
     * @param request      AppendEntries request to send
     * @return CompletableFuture that will complete with response or an exception
     */
    CompletableFuture<AppendEntriesResponse> sendAppendEntries(String targetNodeId, AppendEntriesRequest request);

    /**
     * Registers a handler for incoming RequestVote RPCs
     * 
     * @param handler function that processes requests and returns response
     */
    void registerRequestVoteHandler(Function<RequestVoteRequest, RequestVoteResponse> handler);

    /**
     * Registers a handler for incoming AppendEntries RPCs
     * 
     * @param handler function that processes requests and returns responses
     */
    void registerAppendEntriesHandler(Function<AppendEntriesRequest, AppendEntriesResponse> handler);

    /**
     * Start the RPC service
     */
    void start();

    /**
     * Stops the RPC service and release resources
     */
    void stop();
}
