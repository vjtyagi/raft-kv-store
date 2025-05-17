package com.example.rpc;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import com.example.model.AppendEntriesRequest;
import com.example.model.AppendEntriesResponse;
import com.example.model.RequestVoteRequest;
import com.example.model.RequestVoteResponse;

public class InMemoryRaftRpcService implements RaftRpcService {
    private final String nodeId;
    private final ExecutorService executor;
    private final Map<String, InMemoryRaftRpcService> nodeRegistry = new ConcurrentHashMap<>();
    private Function<RequestVoteRequest, RequestVoteResponse> requestVoteHandler;
    private Function<AppendEntriesRequest, AppendEntriesResponse> appendEntriesHandler;

    /**
     * Creates a new in-memory RPC service for the specified node
     * 
     * @param nodeId Id of the node this service belongs to
     */
    public InMemoryRaftRpcService(String nodeId) {
        this.nodeId = nodeId;
        this.executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(), r -> {
            Thread t = new Thread(r, "rpc-worker-" + nodeId);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Registers other nodes in the cluster for communication
     * 
     * @param nodeId     Id of the node to register
     * @param rpcService RPC service for that ndoe
     */
    public void registerNode(String nodeId, InMemoryRaftRpcService rpcService) {
        nodeRegistry.put(nodeId, rpcService);
    }

    public boolean unregisterNode(String nodeId) {
        if (!nodeRegistry.containsKey(nodeId)) {
            System.out.println(this.nodeId + ": Node " + nodeId + " already unregistered");
            return false;
        }
        InMemoryRaftRpcService removed = nodeRegistry.remove(nodeId);
        System.out.println(this.nodeId + " successfully unregisterd node " + nodeId);
        return (removed != null);
    }

    @Override
    public CompletableFuture<RequestVoteResponse> sendRequestVote(String targetNodeId, RequestVoteRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            InMemoryRaftRpcService targetNode = nodeRegistry.get(targetNodeId);
            if (targetNode == null) {
                throw new IllegalArgumentException("Unknown node: " + targetNodeId);
            }
            return targetNode.handleRequestVote(request);
        }, executor);
    }

    @Override
    public CompletableFuture<AppendEntriesResponse> sendAppendEntries(String targetNodeId,
            AppendEntriesRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            InMemoryRaftRpcService targetNode = nodeRegistry.get(targetNodeId);
            if (targetNode == null) {
                throw new IllegalArgumentException("Unknown node: " + targetNodeId);
            }
            return targetNode.handleAppendEntries(request);
        }, executor);
    }

    @Override
    public void registerRequestVoteHandler(Function<RequestVoteRequest, RequestVoteResponse> handler) {
        this.requestVoteHandler = handler;
    }

    @Override
    public void registerAppendEntriesHandler(Function<AppendEntriesRequest, AppendEntriesResponse> handler) {
        this.appendEntriesHandler = handler;
    }

    @Override
    public void start() {
        // Nothing to do for in memory implementation
    }

    @Override
    public void stop() {
        executor.shutdown();
    }

    /**
     * Handles an incoming RequestVote RPC by delegating to the registered handler
     * 
     */
    private RequestVoteResponse handleRequestVote(RequestVoteRequest request) {
        if (requestVoteHandler == null) {
            throw new IllegalStateException("No RequestVote handler registered for node " + nodeId);
        }
        return requestVoteHandler.apply(request);
    }

    private AppendEntriesResponse handleAppendEntries(AppendEntriesRequest request) {
        if (appendEntriesHandler == null) {
            throw new IllegalStateException("No AppendEntries handler registered for node " + nodeId);
        }
        return appendEntriesHandler.apply(request);
    }

}
