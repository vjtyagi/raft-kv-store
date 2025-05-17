package com.example.rpc;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.verification.Timeout;

import com.example.model.AppendEntriesRequest;
import com.example.model.AppendEntriesResponse;
import com.example.model.RequestVoteRequest;
import com.example.model.RequestVoteResponse;

public class InMemoryRaftRpcServiceTest {
    private InMemoryRaftRpcService node1RpcService;
    private InMemoryRaftRpcService node2RpcService;
    private InMemoryRaftRpcService node3RpcService;

    private Function<RequestVoteRequest, RequestVoteResponse> mockRequestVoteHandler;
    private Function<AppendEntriesRequest, AppendEntriesResponse> mockAppendEntriesHandler;

    @SuppressWarnings("unchecked")
    @BeforeEach
    public void setup() {
        node1RpcService = new InMemoryRaftRpcService("node1");
        node2RpcService = new InMemoryRaftRpcService("node2");
        node3RpcService = new InMemoryRaftRpcService("node3");

        // Register nodes with each other
        node1RpcService.registerNode("node2", node2RpcService);
        node1RpcService.registerNode("node3", node3RpcService);

        node2RpcService.registerNode("node1", node1RpcService);
        node2RpcService.registerNode("node3", node3RpcService);

        node3RpcService.registerNode("node1", node1RpcService);
        node3RpcService.registerNode("node2", node2RpcService);

        // Mock handlers using mockito
        mockRequestVoteHandler = (Function<RequestVoteRequest, RequestVoteResponse>) mock(Function.class);
        mockAppendEntriesHandler = (Function<AppendEntriesRequest, AppendEntriesResponse>) mock(Function.class);

        // Register handlers with each rpcService
        node1RpcService.registerRequestVoteHandler(mockRequestVoteHandler);
        node1RpcService.registerAppendEntriesHandler(mockAppendEntriesHandler);

        node2RpcService.registerRequestVoteHandler(mockRequestVoteHandler);
        node2RpcService.registerAppendEntriesHandler(mockAppendEntriesHandler);

        node3RpcService.registerRequestVoteHandler(mockRequestVoteHandler);
        node3RpcService.registerAppendEntriesHandler(mockAppendEntriesHandler);

        // Start Rpc services
        node1RpcService.start();
        node2RpcService.start();
        node3RpcService.start();
    }

    @AfterEach
    public void tearDown() {
        node1RpcService.stop();
        node2RpcService.stop();
        node3RpcService.stop();
    }

    @Test
    public void testSendRequestVote() throws InterruptedException, ExecutionException, TimeoutException {
        // setup
        RequestVoteRequest request = new RequestVoteRequest(1, "node1", 0, 0);
        RequestVoteResponse expectedResponse = new RequestVoteResponse(1, true);

        when(mockRequestVoteHandler.apply(request)).thenReturn(expectedResponse);

        // Act
        CompletableFuture<RequestVoteResponse> future = node1RpcService.sendRequestVote("node2", request);
        RequestVoteResponse response = future.get(1, TimeUnit.SECONDS);
        // assert
        assertEquals(expectedResponse.getTerm(), response.getTerm(), "Term should match expected response");
        assertEquals(expectedResponse.isVoteGranted(), response.isVoteGranted(),
                "VoteGranted should match expected response");
        verify(mockRequestVoteHandler, times(1)).apply(request);
    }

    @Test
    public void testSendAppendEntries() throws InterruptedException, ExecutionException, TimeoutException {
        // setup
        AppendEntriesRequest request = mock(AppendEntriesRequest.class);
        when(request.getTerm()).thenReturn(1);
        when(request.getLeaderId()).thenReturn("node1");

        AppendEntriesResponse expectedResponse = new AppendEntriesResponse(1, true);

        when(mockAppendEntriesHandler.apply(request)).thenReturn(expectedResponse);

        // act
        CompletableFuture<AppendEntriesResponse> future = node1RpcService.sendAppendEntries("node3", request);
        AppendEntriesResponse response = future.get(1, TimeUnit.SECONDS);

        // Assert
        assertEquals(expectedResponse.getTerm(), response.getTerm(), "Term should match expected response");
        assertEquals(expectedResponse.isSuccess(), response.isSuccess(), "Success should match expected response");
        verify(mockAppendEntriesHandler, times(1)).apply(request);

    }

    @Test
    public void testUnregisterNode() throws InterruptedException, ExecutionException, TimeoutException {
        // setup
        RequestVoteRequest request = new RequestVoteRequest(1, "node1", 0, 0);
        // unregister node3 from node1's registry
        assertTrue(node1RpcService.unregisterNode("node3"), "unergister should return true for known node");

        // Act and assert
        CompletableFuture<RequestVoteResponse> future = node1RpcService.sendRequestVote("node3", request);

        // Should throw exception
        Exception exception = assertThrows(Exception.class, () -> future.get(1, TimeUnit.SECONDS));

        assertTrue(exception instanceof ExecutionException, "ExecutionException expected");
        assertTrue(exception.getCause() instanceof IllegalArgumentException,
                "Cause should be IllegalArgumentException for unknown node");
    }

    @Test
    public void testUnregisterNonExistentNode() {
        assertFalse(node1RpcService.unregisterNode("non_existent_node"),
                "Unregister should return false for unknown node");
    }

    @Test
    public void testNoRequestVoteHandler() throws InterruptedException {
        // setup
        InMemoryRaftRpcService noHandlerService = new InMemoryRaftRpcService("noHandler");
        noHandlerService.start();

        InMemoryRaftRpcService callerService = new InMemoryRaftRpcService("caller");
        callerService.start();

        callerService.registerNode("noHandler", noHandlerService);

        // no handler registered for noHandlerService
        RequestVoteRequest request = new RequestVoteRequest(1, "caller", 0, 0);

        // act
        CompletableFuture<RequestVoteResponse> future = callerService.sendRequestVote("noHandler", request);

        // assert
        Exception exception = assertThrows(Exception.class, () -> future.get(1, TimeUnit.SECONDS));

        assertTrue(exception instanceof ExecutionException, "ExecutionException expected");
        assertTrue(exception.getCause() instanceof IllegalStateException,
                "cause should be IllegalStateException when no handler is registered");

        // cleanup
        noHandlerService.stop();
        callerService.stop();
    }

    @Test
    public void testNoAppendEntriesHandler() throws InterruptedException {
        // setup
        InMemoryRaftRpcService noHandlerService = new InMemoryRaftRpcService("noHandler");
        noHandlerService.start();
        InMemoryRaftRpcService callerService = new InMemoryRaftRpcService("caller");
        callerService.start();

        callerService.registerNode("noHandler", noHandlerService);

        AppendEntriesRequest request = mock(AppendEntriesRequest.class);

        CompletableFuture<AppendEntriesResponse> future = callerService.sendAppendEntries("noHandler", request);

        Exception exception = assertThrows(Exception.class, () -> future.get(1, TimeUnit.SECONDS));
        assertTrue(exception instanceof ExecutionException, "Execution exception expected");
        assertTrue(exception.getCause() instanceof IllegalStateException,
                "Cause should be IllegalStateException when no handler registered");

        noHandlerService.stop();
        callerService.stop();
    }

    @Test
    public void testConcurrentRPCs() throws InterruptedException, ExecutionException, TimeoutException {

        // setup - Prepare response for multiple concurrent requests
        RequestVoteRequest request = new RequestVoteRequest(1, "node1", 0, 0);
        RequestVoteResponse expectedResponse = new RequestVoteResponse(1, true);

        when(mockRequestVoteHandler.apply(any(RequestVoteRequest.class))).thenReturn(expectedResponse);

        // act - send multiple concurrent RPCs
        int concurrentRequests = 50;
        CompletableFuture<RequestVoteResponse>[] futures = new CompletableFuture[concurrentRequests];
        for (int i = 0; i < concurrentRequests; i++) {
            futures[i] = node1RpcService.sendRequestVote("node2", request);
        }
        // Wait for all to complete
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(futures);
        allFutures.get(5, TimeUnit.SECONDS); // wait for all to complete with a timeout

        // Assert All should complete successfully
        for (CompletableFuture<RequestVoteResponse> future : futures) {
            RequestVoteResponse response = future.get();
            assertEquals(expectedResponse.getTerm(), response.getTerm());
            assertEquals(expectedResponse.isVoteGranted(), response.isVoteGranted());
        }
        verify(mockRequestVoteHandler, times(concurrentRequests)).apply(any(RequestVoteRequest.class));

    }

}
