package com.example.networking.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.model.AppendEntriesRequest;
import com.example.model.AppendEntriesResponse;
import com.example.model.RequestVoteRequest;
import com.example.model.RequestVoteResponse;
import com.example.networking.rpc.HttpRaftRpcService;

@ExtendWith(MockitoExtension.class)
public class RaftControllerTest {
    @Mock
    private HttpRaftRpcService raftRpcService;

    @InjectMocks
    private RaftController raftController;

    @Test
    void testHandleAppendEntries() {
        AppendEntriesRequest request = new AppendEntriesRequest(1, "leader1", 0, 0, Collections.emptyList(), 0);
        AppendEntriesResponse expectedResponse = new AppendEntriesResponse(1, true);
        when(raftRpcService.handleAppendEntries(request)).thenReturn(expectedResponse);
        AppendEntriesResponse response = raftController.appendEntries(request);
        assertEquals(expectedResponse, response);
        verify(raftRpcService).handleAppendEntries(request);
    }

    @Test
    void testHandleRequestVote() {
        RequestVoteRequest request = new RequestVoteRequest(1, "node1", 0, 0);
        RequestVoteResponse expectedResponse = new RequestVoteResponse(1, true);
        when(raftRpcService.handleRequestVote(request)).thenReturn(expectedResponse);

        RequestVoteResponse response = raftController.requestVote(request);
        assertEquals(expectedResponse, response);
        verify(raftRpcService).handleRequestVote(request);
    }
}
