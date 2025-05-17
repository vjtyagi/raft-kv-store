package com.example.networking.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.platform.commons.support.Resource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import com.example.model.AppendEntriesRequest;
import com.example.model.AppendEntriesResponse;
import com.example.model.RequestVoteRequest;
import com.example.model.RequestVoteResponse;
import com.example.networking.config.NetworkConfig;

@ExtendWith(MockitoExtension.class)
public class HttpRaftRpcServiceTest {

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private NetworkConfig networkConfig;

    private HttpRaftRpcService rpcService;

    @BeforeEach
    public void setUp() {
        when(networkConfig.createRestTemplate()).thenReturn(restTemplate);
        rpcService = new HttpRaftRpcService(networkConfig);
        rpcService.start();
    }

    @Test
    public void testSendAppendEntriesSuccess() throws Exception {
        AppendEntriesRequest request = new AppendEntriesRequest(1, "leader", 0, 0, List.of(), 0);
        AppendEntriesResponse expectedResponse = new AppendEntriesResponse(1, true);

        when(networkConfig.getNodeUrl("node2")).thenReturn("http://node2:8082");
        when(restTemplate.postForObject(
                eq("http://node2:8082/raft/append-entries"),
                eq(request),
                eq(AppendEntriesResponse.class))).thenReturn(expectedResponse);
        AppendEntriesResponse response = rpcService.sendAppendEntries("node2", request).get();
        assertEquals(expectedResponse.getTerm(), response.getTerm());
        assertEquals(expectedResponse.isSuccess(), response.isSuccess());
    }

    @Test
    void testSendRequestVoteSuccess() throws Exception {
        RequestVoteRequest request = new RequestVoteRequest(1, "candidate", 0, 0);
        RequestVoteResponse expectedResponse = new RequestVoteResponse(1, true);
        when(networkConfig.getNodeUrl("node2")).thenReturn("http://node2:8082");
        when(restTemplate.postForObject(
                eq("http://node2:8082/raft/request-vote"),
                eq(request),
                eq(RequestVoteResponse.class))).thenReturn(expectedResponse);
        RequestVoteResponse response = rpcService.sendRequestVote("node2", request).get();
        assertEquals(expectedResponse.getTerm(), response.getTerm());
        assertEquals(expectedResponse.isVoteGranted(), response.isVoteGranted());

    }

    @Test
    void testHandleIncomingRequestVote() {
        RequestVoteRequest request = new RequestVoteRequest(1, "candidate", 0, 0);
        RequestVoteResponse expectedResponse = new RequestVoteResponse(1, true);
        rpcService.registerRequestVoteHandler(req -> expectedResponse);

        RequestVoteResponse response = rpcService.handleRequestVote(request);
        assertEquals(expectedResponse, response);
    }

    @Test
    void testHandleIncomingAppendEntries() {
        AppendEntriesRequest request = new AppendEntriesRequest(1, "leader", 0, 0, List.of(), 0);
        AppendEntriesResponse expectedResponse = new AppendEntriesResponse(1, true);
        rpcService.registerAppendEntriesHandler(req -> expectedResponse);

        AppendEntriesResponse response = rpcService.handleAppendEntries(request);
        assertEquals(expectedResponse, response);
    }

    @Test
    void testServiceNotStart() {
        rpcService.stop();
        RequestVoteRequest request = new RequestVoteRequest(1, "candidate", 0, 0);
        assertThrows(ExecutionException.class, () -> rpcService.sendRequestVote("node2", request).get());
    }

    @Test
    void testNetworkFailure() {
        RequestVoteRequest request = new RequestVoteRequest(1, "candidate", 0, 0);
        when(networkConfig.getNodeUrl("node2")).thenReturn("http://node2:8082");
        when(restTemplate.postForObject(
                anyString(),
                any(),
                any())).thenThrow(new RuntimeException("Network error"));

        // Act & Assert
        assertThrows(ExecutionException.class, () -> rpcService.sendRequestVote("node2", request).get());
    }
}
