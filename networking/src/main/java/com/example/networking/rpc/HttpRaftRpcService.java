package com.example.networking.rpc;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import com.example.model.AppendEntriesRequest;
import com.example.model.AppendEntriesResponse;
import com.example.model.RequestVoteRequest;
import com.example.model.RequestVoteResponse;
import com.example.networking.config.NetworkConfig;
import com.example.rpc.RaftRpcService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class HttpRaftRpcService implements RaftRpcService {
    private final RestTemplate restTemplate;
    private final NetworkConfig networkConfig;
    private volatile boolean running = false;

    // RPC handlers
    private volatile Function<RequestVoteRequest, RequestVoteResponse> requestVoteHandler;
    private volatile Function<AppendEntriesRequest, AppendEntriesResponse> appendEntriesHandler;

    public HttpRaftRpcService(NetworkConfig networkConfig) {
        this.networkConfig = networkConfig;
        this.restTemplate = networkConfig.createRestTemplate();
    }

    @Override
    public void registerAppendEntriesHandler(Function<AppendEntriesRequest, AppendEntriesResponse> handler) {
        this.appendEntriesHandler = handler;
    }

    @Override
    public void registerRequestVoteHandler(Function<RequestVoteRequest, RequestVoteResponse> handler) {
        this.requestVoteHandler = handler;
    }

    @Override
    public CompletableFuture<AppendEntriesResponse> sendAppendEntries(String targetNodeId,
            AppendEntriesRequest request) {

        if (!running) {
            return CompletableFuture.failedFuture(new IllegalStateException("RPC service not started"));
        }

        return CompletableFuture.supplyAsync(() -> {
            String url = networkConfig.getNodeUrl(targetNodeId) + "/raft/append-entries";
            log.debug("Sending appendEntries to {} at {}", targetNodeId, url);
            try {
                log.debug("Sending AppendEntries to {}: {}", targetNodeId, request);
                AppendEntriesResponse response = restTemplate.postForObject(
                        url, request, AppendEntriesResponse.class);
                log.debug("Received AppendEntries response from {}: {}", targetNodeId, response);
                return response;
            } catch (ResourceAccessException e) {
                log.warn("Failed to send AppendEntries: {}, {}", targetNodeId, e.getMessage());
                return new AppendEntriesResponse(request.getTerm(), false);
            } catch (Exception e) {
                log.error("Error sending AppendEntries: {}, {}", targetNodeId, e.getMessage());
                throw e;
            }
        });
    }

    @Override
    public CompletableFuture<RequestVoteResponse> sendRequestVote(String targetNodeId, RequestVoteRequest request) {
        if (!running) {
            return CompletableFuture.failedFuture(new IllegalStateException("RPC service not started"));
        }
        return CompletableFuture.supplyAsync(() -> {
            String url = networkConfig.getNodeUrl(targetNodeId) + "/raft/request-vote";
            log.info("Sending RequestVote to {} at url {}", targetNodeId, url);
            try {
                log.debug("Sending RequestVote to {}: {}", targetNodeId, request);
                RequestVoteResponse response = restTemplate.postForObject(url, request, RequestVoteResponse.class);
                log.debug("Received RequestVote response from {}: {}", targetNodeId, response);
                return response;
            } catch (ResourceAccessException e) {
                log.warn("Failed to send RequestVote to : {}, {}", targetNodeId, e.getMessage());
                return new RequestVoteResponse(0, false);
            } catch (Exception e) {
                log.error("Error sending RequestVote to: {}, {}", targetNodeId, e.getMessage());
                throw e;
            }

        });
    }

    @Override
    public void start() {
        running = true;
        log.info("Http RPC service started");
    }

    @Override
    public void stop() {
        running = false;
        log.info("Http RPC Service stopped");
    }

    // For use by RaftController
    public RequestVoteResponse handleRequestVote(RequestVoteRequest request) {
        if (!running || requestVoteHandler == null) {
            throw new IllegalStateException("RPC service not ready to handle requests");
        }
        return requestVoteHandler.apply(request);
    }

    // For use by RaftController
    public AppendEntriesResponse handleAppendEntries(AppendEntriesRequest request) {
        if (!running || appendEntriesHandler == null) {
            throw new IllegalStateException("RPC service not ready to handle requets");
        }
        return appendEntriesHandler.apply(request);
    }

}
