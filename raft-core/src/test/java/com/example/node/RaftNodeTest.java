package com.example.node;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.example.log.LogManager;
import com.example.model.AppendEntriesRequest;
import com.example.model.AppendEntriesResponse;
import com.example.model.RaftLogEntry;
import com.example.model.RequestVoteRequest;
import com.example.model.RequestVoteResponse;
import com.example.raft.statemachine.StateMachine;
import com.example.rpc.RaftRpcService;
import com.example.state.RaftState;
import com.example.timer.ElectionTimer;
import com.example.timer.HeartBeatTimer;

public class RaftNodeTest {
    private static final String NODE_ID = "node1";
    private static List<String> PEER_IDS = Arrays.asList("node2", "node3");

    private RaftNode raftNode;
    private LogManager mockLogManager;
    private RaftRpcService mockRpcService;
    private ElectionTimer mockElectionTimer;
    private HeartBeatTimer mockHeartBeatTimer;
    private StateMachine mockStateMachine;

    @BeforeEach
    public void setup() {
        mockLogManager = mock(LogManager.class);
        mockRpcService = mock(RaftRpcService.class);
        mockElectionTimer = mock(ElectionTimer.class);
        mockHeartBeatTimer = mock(HeartBeatTimer.class);
        mockStateMachine = mock(StateMachine.class);

        // configure default behaviour for mocks
        when(mockLogManager.getLastLogIndex()).thenReturn(-1);
        when(mockLogManager.getLastLogTerm()).thenReturn(0);
        when(mockLogManager.getLogEntryTerm(anyInt())).thenReturn(0);
        when(mockLogManager.hasLogEntry(anyInt())).thenReturn(false);
        when(mockLogManager.append(anyInt(), anyString())).thenReturn(0);
        when(mockLogManager.appendEntries(anyInt(), anyList())).thenReturn(true);
        when(mockLogManager.size()).thenReturn(0);

        // setup CompletableFuture responses for RPCs
        CompletableFuture<RequestVoteResponse> requestVoteFuture = CompletableFuture
                .completedFuture(new RequestVoteResponse(0, false));

        when(mockRpcService.sendRequestVote(anyString(), any(RequestVoteRequest.class)))
                .thenReturn(requestVoteFuture);

        CompletableFuture<AppendEntriesResponse> appendEntriesFuture = CompletableFuture
                .completedFuture(new AppendEntriesResponse(0, true));
        when(mockRpcService.sendAppendEntries(anyString(), any(AppendEntriesRequest.class)))
                .thenReturn(appendEntriesFuture);

        raftNode = new RaftNode(NODE_ID, PEER_IDS, mockLogManager, mockRpcService, mockElectionTimer,
                mockHeartBeatTimer);
    }

    @Nested
    @DisplayName("Initial State tests")
    class InitialStateTests {
        @Test
        @DisplayName("Should initialize as follower with term 0")
        void testInitialState() {
            assertEquals(RaftState.FOLLOWER, raftNode.getState());
            assertEquals(0, raftNode.getCurrentTerm());
            assertNull(raftNode.getVotedFor());
            assertEquals(NODE_ID, raftNode.getNodeId());
            assertEquals(PEER_IDS, raftNode.getPeers());
        }

        @Test
        @DisplayName("Should register handlers during constructor")
        void testHandlersRegistered() {
            verify(mockElectionTimer).setTimeoutHandler(any(Runnable.class));
            verify(mockHeartBeatTimer).setHeartBeatHandler(any(Runnable.class));
            verify(mockRpcService).registerRequestVoteHandler(any());
            verify(mockRpcService).registerAppendEntriesHandler(any());
        }
    }

    @Nested
    @DisplayName("Node Lifecycle Tests")
    class LifecycleTests {
        @Test
        @DisplayName("Should start properly as follower")
        void testStart() {
            raftNode.start();
            verify(mockElectionTimer).start();
            verify(mockRpcService).start();
            verify(mockHeartBeatTimer, never()).start();
        }

        @Test
        @DisplayName("Should stop all components")
        void testStop() {
            raftNode.start();
            raftNode.stop();
            verify(mockElectionTimer).stop();
            verify(mockRpcService).stop();
            verify(mockHeartBeatTimer).stop();
        }

        @Test
        @DisplayName("Should transition to follower state properly")
        void testTransitionToFollower() {
            triggerElectionTimeout();
            assertEquals(RaftState.CANDIDATE, raftNode.getState());
            raftNode.handleAppendEntries(new AppendEntriesRequest(2, "leader", -1, 0, List.of(), 0));
            assertEquals(RaftState.FOLLOWER, raftNode.getState());
        }
    }

    @Nested
    @DisplayName("Election Tests")
    class ElectionTests {
        @Test
        @DisplayName("Should start election on timeout")
        void testElectionTimeout() {
            triggerElectionTimeout();
            assertEquals(RaftState.CANDIDATE, raftNode.getState());
            assertEquals(1, raftNode.getCurrentTerm());
            assertEquals(NODE_ID, raftNode.getVotedFor());
            verify(mockElectionTimer).reset();
        }

        @Test
        @DisplayName("Should send RequestVote to all peers")
        void testSendRequestVoteToPeers() {
            triggerElectionTimeout();
            verify(mockRpcService, times(PEER_IDS.size())).sendRequestVote(anyString(), any(RequestVoteRequest.class));
        }

        @Test
        @DisplayName("Should become leader after majority votes")
        void testBecomeLeaderWithMajority() {
            when(mockRpcService.sendRequestVote(anyString(), any(RequestVoteRequest.class)))
                    .thenReturn(CompletableFuture.completedFuture(new RequestVoteResponse(1, true)));
            triggerElectionTimeout();

            waitForState(RaftState.LEADER);
            assertEquals(RaftState.LEADER, raftNode.getState());
            verify(mockHeartBeatTimer).start();
        }

        @Test
        @DisplayName("Should not become leader without majority")
        void testNoMajority() {
            when(mockRpcService.sendRequestVote(eq("node2"), any(RequestVoteRequest.class)))
                    .thenReturn(CompletableFuture.completedFuture(new RequestVoteResponse(1, false)));
            when(mockRpcService.sendRequestVote(eq("node3"), any(RequestVoteRequest.class)))
                    .thenReturn(CompletableFuture.completedFuture(new RequestVoteResponse(1, false)));

            triggerElectionTimeout();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
            }
            assertEquals(RaftState.CANDIDATE, raftNode.getState());
        }

        @Test
        @DisplayName("Should step down if higher term received during election")
        void testStepDownDuringElection() {

            when(mockRpcService.sendRequestVote(anyString(), any(RequestVoteRequest.class)))
                    .thenReturn(CompletableFuture.completedFuture(new RequestVoteResponse(1, false)));
            triggerElectionTimeout();
            assertEquals(RaftState.CANDIDATE, raftNode.getState());

            RequestVoteRequest request = new RequestVoteRequest(5, "otherNode", 0, 0);
            raftNode.handleRequestVote(request);

            waitForState(RaftState.FOLLOWER);
            assertEquals(RaftState.FOLLOWER, raftNode.getState());
            assertEquals(5, raftNode.getCurrentTerm());
            assertEquals("otherNode", raftNode.getVotedFor());
        }
    }

    @Nested
    @DisplayName("Request vote handling tests")
    class RequestVoteTests {
        @Test
        @DisplayName("Should grant vote to valid candidate")
        void testGrantVoteToValidCandidate() {
            RequestVoteRequest request = new RequestVoteRequest(1, "candidate", 0, 0);
            RequestVoteResponse response = raftNode.handleRequestVote(request);
            assertTrue(response.isVoteGranted());
            assertEquals(1, response.getTerm());
            assertEquals(1, raftNode.getCurrentTerm());
            assertEquals("candidate", raftNode.getVotedFor());
            verify(mockElectionTimer).reset();
        }

        @Test
        @DisplayName("Should deny vote for older term")
        void testDenyVoteForOlderTerm() {
            raftNode.handleRequestVote(new RequestVoteRequest(2, "other", 0, 0));

            RequestVoteRequest request = new RequestVoteRequest(1, "candidate", 0, 0);
            RequestVoteResponse response = raftNode.handleRequestVote(request);
            assertFalse(response.isVoteGranted());
            assertEquals(2, response.getTerm());
        }

        @Test
        @DisplayName("Should deny vote if already voted for another candidate")
        void testDenyVoteIfAlreadyVoted() {
            raftNode.handleRequestVote(new RequestVoteRequest(1, "candidate", 0, 0));
            RequestVoteRequest request = new RequestVoteRequest(1, "candidate2", 0, 0);
            RequestVoteResponse response = raftNode.handleRequestVote(request);
            assertFalse(response.isVoteGranted());
            assertEquals("candidate", raftNode.getVotedFor());
        }

        @Test
        @DisplayName("Should deny vote if candidate log is outdated")
        void testDenyVoteForOutdatedLog() {
            when(mockLogManager.getLastLogIndex()).thenReturn(2);
            when(mockLogManager.getLastLogTerm()).thenReturn(2);
            RequestVoteRequest request = new RequestVoteRequest(3, "candidate", 1, 1);
            RequestVoteResponse response = raftNode.handleRequestVote(request);
            assertFalse(response.isVoteGranted());
        }

        @Test
        @DisplayName("Should grant vote if candidate log is more up to date")
        void testGrantVoteForUptodateLog() {
            when(mockLogManager.getLastLogIndex()).thenReturn(1);
            when(mockLogManager.getLastLogTerm()).thenReturn(1);

            RequestVoteRequest request = new RequestVoteRequest(2, "candidate", 2, 2);
            RequestVoteResponse response = raftNode.handleRequestVote(request);
            assertTrue(response.isVoteGranted());
        }

        @Test
        @DisplayName("should not vote as a candidate")
        void testShouldNotVoteAsCandidate() {
            triggerElectionTimeout();
            assertEquals(RaftState.CANDIDATE, raftNode.getState());

            RequestVoteResponse response = raftNode.handleRequestVote(new RequestVoteRequest(1, "other", 0, 0));
            assertFalse(response.isVoteGranted());
        }

        @Test
        @DisplayName("should not vote as leader")
        void testShouldNotVoteAsLeader() {
            becomeLeader();
            RequestVoteRequest request = new RequestVoteRequest(1, "other", 0, 0);
            RequestVoteResponse response = raftNode.handleRequestVote(request);
            assertFalse(response.isVoteGranted());
        }
    }

    @Nested
    @DisplayName("AppendEntries Handling tests")
    class AppendEntriesTests {
        @Test
        @DisplayName("Shoudl accept valid append entries as follower")
        void testAcceptValidAppendEntries() {
            List<RaftLogEntry> entries = List.of(new RaftLogEntry(0, 1, "SET x 10"));
            AppendEntriesRequest request = new AppendEntriesRequest(1, "leader", -1, 0, entries, 0);
            AppendEntriesResponse response = raftNode.handleAppendEntries(request);
            assertTrue(response.isSuccess());
            assertEquals(1, response.getTerm());
            verify(mockLogManager).appendEntries(eq(-1), eq(entries));
            verify(mockElectionTimer).reset();
        }

        @Test
        @DisplayName("Should step down if receving entries from higher term")
        void testStepDownOnHigherTerm() {
            becomeLeader();
            AppendEntriesRequest request = new AppendEntriesRequest(5, "newLeader", -1, 0, List.of(), 0);
            AppendEntriesResponse response = raftNode.handleAppendEntries(request);
            assertEquals(RaftState.FOLLOWER, raftNode.getState());
            assertEquals(5, raftNode.getCurrentTerm());
            assertNull(raftNode.getVotedFor());
            assertTrue(response.isSuccess());
        };

        @Test
        @DisplayName("Should reject older term append entries")
        void testRejectOlderTermAppendEntries() {
            raftNode.handleRequestVote(new RequestVoteRequest(3, "candidate", 0, 0));
            AppendEntriesRequest request = new AppendEntriesRequest(2, "leader", -1, 0, List.of(), 0);
            AppendEntriesResponse response = raftNode.handleAppendEntries(request);
            assertFalse(response.isSuccess());
            assertEquals(3, response.getTerm());
        }

        @Test
        @DisplayName("Should update commit index when leader commit is higher")
        void testUpdateCommitIndex() {
            when(mockLogManager.getLastLogIndex()).thenReturn(2);
            when(mockLogManager.getLogEntry(0)).thenReturn(new RaftLogEntry(0, 1, "cmd1"));
            when(mockLogManager.getLogEntry(1)).thenReturn(new RaftLogEntry(1, 1, "cmd2"));
            when(mockStateMachine.apply(any())).thenReturn(true);

            AppendEntriesRequest request = new AppendEntriesRequest(1, "leader", -1, 0, List.of(), 2);
            AppendEntriesResponse response = raftNode.handleAppendEntries(request);
            assertTrue(response.isSuccess());
            verify(mockLogManager, times(3)).getLogEntry(anyInt());
        }

    }

    @Test
    public void testInitialState() {
        assertEquals(RaftState.FOLLOWER, raftNode.getState());
        assertEquals(0, raftNode.getCurrentTerm());
        assertNull(raftNode.getVotedFor());
    }

    @Test
    public void testStartNodeAsFollower() {
        // act
        raftNode.start();

        // verify
        verify(mockElectionTimer).start();
        verify(mockRpcService).start();
        verify(mockHeartBeatTimer, never()).start();
    }

    @Test
    public void testStopNode() {
        raftNode.stop();

        verify(mockElectionTimer).stop();
        verify(mockRpcService).stop();
        verify(mockHeartBeatTimer).stop();
    }

    @Test
    public void testOnElectionTimeout() {
        ArgumentCaptor<Runnable> electionTimeoutHandlerCaptor = ArgumentCaptor.forClass(Runnable.class);

        verify(mockElectionTimer).setTimeoutHandler(electionTimeoutHandlerCaptor.capture());

        Runnable electionTimeoutHandler = electionTimeoutHandlerCaptor.getValue();

        // Act - simulate election timeout
        electionTimeoutHandler.run();

        // Assert - should transition to candidate and start election
        assertEquals(RaftState.CANDIDATE, raftNode.getState());
        assertEquals(1, raftNode.getCurrentTerm());
        assertEquals(NODE_ID, raftNode.getVotedFor());

        // Verify electiontimer was reset
        verify(mockElectionTimer).reset();

        // Verify RequestVoteRPCs were sent to peers
        verify(mockRpcService, times(PEER_IDS.size())).sendRequestVote(anyString(), any(RequestVoteRequest.class));

    }

    @Test
    public void testBecomeLeaderAfterMajorityVotes() {
        ArgumentCaptor<Runnable> electionTimeoutHandlerCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(mockElectionTimer).setTimeoutHandler(electionTimeoutHandlerCaptor.capture());
        Runnable electionTimeoutHandler = electionTimeoutHandlerCaptor.getValue();

        // configure RPC responses for granting votes
        when(mockRpcService.sendRequestVote(anyString(), any(RequestVoteRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(new RequestVoteResponse(1, true)));

        // Run election timeout handler
        electionTimeoutHandler.run();

        assertEquals(RaftState.LEADER, raftNode.getState());
        // verify heartbeat timer started
        verify(mockHeartBeatTimer).start();

        // Verify AppendEntries RPCs were sent (heartbeats)
        verify(mockRpcService, atLeastOnce()).sendAppendEntries(anyString(), any(AppendEntriesRequest.class));
    }

    @Test
    public void testHandleRequestVoteAsFollower() {
        // Question: why vote getting granted? even though in setup we're rejecting vote
        RequestVoteRequest request = new RequestVoteRequest(1, "candidate", 1, 1);

        RequestVoteResponse response = raftNode.handleRequestVote(request);

        assertTrue(response.isVoteGranted());
        assertEquals(1, response.getTerm());
        assertEquals(1, raftNode.getCurrentTerm());
        assertEquals("candidate", raftNode.getVotedFor());
    }

    @Test
    public void testHandleRequestVoteAsFollower_DenyOlderTerm() {
        // setup - set node's term higher than request
        // this will update current term of the node to 2
        raftNode.handleRequestVote(new RequestVoteRequest(2, "other", 0, 0));

        // Now try with a lower term
        RequestVoteRequest request = new RequestVoteRequest(1, "candidate", 0, 0);

        RequestVoteResponse response = raftNode.handleRequestVote(request);

        assertFalse(response.isVoteGranted());
        assertEquals(2, response.getTerm());
    }

    @Test
    public void testHandleRequestVoteAsFollower_AlreadyVoted() {
        // setup - already voted for another candidate
        raftNode.handleRequestVote(new RequestVoteRequest(1, "candidate1", 0, 0));

        // Now try with different candidate in same term
        RequestVoteRequest request = new RequestVoteRequest(1, "candidate2", 0, 0);

        RequestVoteResponse response = raftNode.handleRequestVote(request);

        assertFalse(response.isVoteGranted());
        assertEquals(1, response.getTerm());
        assertEquals("candidate1", raftNode.getVotedFor());
    }

    @Test
    public void testHandleAppendEntriesAsFollower() {
        List<RaftLogEntry> entries = List.of(new RaftLogEntry(0, 1, "SET x 10"));
        // prevLogIndex = -1, to indicate the starting of the log, there's no entry in
        // the log so far
        AppendEntriesRequest request = new AppendEntriesRequest(1, "leader", -1, 0, entries, 0);

        // Act
        AppendEntriesResponse response = raftNode.handleAppendEntries(request);

        // assert
        assertTrue(response.isSuccess());
        assertEquals(1, response.getTerm());

        // Verify logEntries were appended
        verify(mockLogManager).appendEntries(eq(-1), eq(entries));

        // verify election timer was reset
        verify(mockElectionTimer).reset();

    }

    @Test
    public void testHandleAppendEntriesAsFollower_ConsistencyCheckFails() {
        // setup
        List<RaftLogEntry> entries = List.of(new RaftLogEntry(1, 1, "SET x 10"));
        AppendEntriesRequest request = new AppendEntriesRequest(1, "leader", 0, 2, entries, 0);

        // Mock log consistency check to fail
        when(mockLogManager.hasLogEntry(0)).thenReturn(true);
        when(mockLogManager.getLogEntryTerm(0)).thenReturn(1);

        // act
        AppendEntriesResponse response = raftNode.handleAppendEntries(request);

        // assert
        assertFalse(response.isSuccess());
        assertEquals(1, response.getTerm());

        // verify log entries were not appended
        verify(mockLogManager, never()).appendEntries(anyInt(), anyList());
    }

    @Test
    public void testHandleAppendEntriesWithHigherTermAsCandidate() {
        // setup - make node a candidate
        ArgumentCaptor<Runnable> electionTimeoutHandlerCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(mockElectionTimer).setTimeoutHandler(electionTimeoutHandlerCaptor.capture());
        Runnable handler = electionTimeoutHandlerCaptor.getValue();

        handler.run(); // this runs onElectiontimeout and transitions node to Candidate

        AppendEntriesRequest request = new AppendEntriesRequest(2, "leader", -1, 0, List.of(), 0);

        // act
        AppendEntriesResponse response = raftNode.handleAppendEntries(request);

        // assert
        assertTrue(response.isSuccess());
        assertEquals(2, response.getTerm());
        assertEquals(RaftState.FOLLOWER, raftNode.getState());
    }

    @Test
    public void testLeaderSendsHeartBeats() {
        // setup capture heartbeat handler
        ArgumentCaptor<Runnable> heartbeatHandlerCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(mockHeartBeatTimer).setHeartBeatHandler(heartbeatHandlerCaptor.capture());
        Runnable heartbeatHandler = heartbeatHandlerCaptor.getValue();

        // Make node a leader
        // First become a candidate
        ArgumentCaptor<Runnable> electionTimeoutHandlerCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(mockElectionTimer).setTimeoutHandler(electionTimeoutHandlerCaptor.capture());
        Runnable electionHandler = electionTimeoutHandlerCaptor.getValue();

        // Configure votes to succeed
        when(mockRpcService.sendRequestVote(anyString(), any(RequestVoteRequest.class)))
                .thenReturn(CompletableFuture.completedFuture((new RequestVoteResponse(1, true))));

        electionHandler.run();

        // Now it should become a leader
        assertEquals(RaftState.LEADER, raftNode.getState());

        // reset mock counts
        reset(mockRpcService);

        when(mockRpcService.sendAppendEntries(anyString(), any(AppendEntriesRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(new AppendEntriesResponse(1, true)));

        // Trigger a heartbeat
        heartbeatHandler.run();

        // verify
        verify(mockRpcService, times(PEER_IDS.size())).sendAppendEntries(anyString(), any(AppendEntriesRequest.class));

    }

    @Test
    public void testAppendCommand() {
        // setup - make node a leader
        // First become a candidate
        ArgumentCaptor<Runnable> electionTimeoutHandlerCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(mockElectionTimer).setTimeoutHandler(electionTimeoutHandlerCaptor.capture());
        Runnable electionHandler = electionTimeoutHandlerCaptor.getValue();

        // configure votes to succeed
        when(mockRpcService.sendRequestVote(anyString(), any(RequestVoteRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(new RequestVoteResponse(1, true)));

        electionHandler.run();
        // should become leader now
        assertEquals(RaftState.LEADER, raftNode.getState());

        // configure log append
        when(mockLogManager.append(anyInt(), anyString())).thenReturn(1);

        // Reset rpc mock
        reset(mockRpcService);

        when(mockRpcService.sendAppendEntries(anyString(), any(AppendEntriesRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(new AppendEntriesResponse(1, true)));

        boolean result = raftNode.appendCommand("SET x 20");

        // assert
        assertTrue(result);
        verify(mockLogManager).append(eq(1), eq("SET x 20"));

        verify(mockRpcService, times(PEER_IDS.size())).sendAppendEntries(anyString(), any(AppendEntriesRequest.class));

    }

    @Test
    public void testAppendCommandAsNonLeader() {
        boolean result = raftNode.appendCommand("SET x 20");
        assertFalse(result);
        verify(mockLogManager, never()).append(anyInt(), anyString());
    }

    @Test
    public void testUpdatePeers() {
        List<String> peers = Arrays.asList("node2", "node4", "node5");

        // act
        raftNode.updatePeers(peers);

        assertEquals(peers, raftNode.getPeers());
    }

    @Test
    public void testProcessAppendEntriesResponse() {
        // setup - make node a leader first
        ArgumentCaptor<Runnable> electionTimeoutHandlerCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(mockElectionTimer).setTimeoutHandler(electionTimeoutHandlerCaptor.capture());
        Runnable electionHandler = electionTimeoutHandlerCaptor.getValue();

        // configure votes to succeed
        when(mockRpcService.sendRequestVote(anyString(), any(RequestVoteRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(new RequestVoteResponse(1, true)));

        electionHandler.run();

        // reset count due to heartbeats
        reset(mockRpcService);

        // Capture AppendEntries request sent to peers
        ArgumentCaptor<AppendEntriesRequest> requestCaptor = ArgumentCaptor.forClass(AppendEntriesRequest.class);

        // Configure successful response that would move commitINdex
        when(mockLogManager.getLastLogIndex()).thenReturn(3);
        when(mockLogManager.getLogEntryTerm(3)).thenReturn(1);

        // Configure future responses to simulate success from all peers
        when(mockRpcService.sendAppendEntries(anyString(), any(AppendEntriesRequest.class)))
                .then(invocation -> {
                    AppendEntriesRequest request = invocation.getArgument(1);
                    return CompletableFuture.completedFuture(new AppendEntriesResponse(request.getTerm(), true));
                });

        // Get heartbeat handler to trigger AppendEntries
        ArgumentCaptor<Runnable> heartbeatHandlerCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(mockHeartBeatTimer).setHeartBeatHandler(heartbeatHandlerCaptor.capture());
        Runnable heartbeatHandler = heartbeatHandlerCaptor.getValue();

        heartbeatHandler.run();
        assertEquals(2, raftNode.getPeers().size());
        verify(mockRpcService, times(PEER_IDS.size())).sendAppendEntries(anyString(), requestCaptor.capture());

    }

    @Test
    public void testNextIndexAndReplicationIndexUpdates() {
        becomeLeader();
        raftNode.setNextIndex("node2", 5);
        raftNode.setReplicationIndex("node2", 2);
        raftNode.setNextIndex("node3", 5);
        raftNode.setReplicationIndex("node3", 2);

        reset(mockRpcService);
        when(mockLogManager.getLastLogIndex()).thenReturn(7);
        when(mockLogManager.getEntriesFrom(anyInt())).thenReturn(List.of(
                new RaftLogEntry(5, 1, "cmd5"),
                new RaftLogEntry(6, 1, "cmd6"),
                new RaftLogEntry(7, 1, "cmd7")));

        when(mockRpcService.sendAppendEntries(anyString(), any(AppendEntriesRequest.class)))
                .thenAnswer(invocation -> {
                    String peerId = invocation.getArgument(0);
                    if ("node2".equals(peerId)) {
                        return CompletableFuture.completedFuture(new AppendEntriesResponse(1, true));
                    } else {
                        return CompletableFuture.completedFuture(new AppendEntriesResponse(1, false));
                    }
                });

        triggerHeartbeat();

        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
        }

        // for node2 ( success )
        // prevlogIndex = nextIndex - 1 = 5 - 1 = 4
        // entries.size() = 3 ( entries 5,6,7)
        // newReplicationIndex = prevLogIndex + entries.size() = 4+3=7
        // newNextIndex = newReplicationIndex + 1 = 8

        assertEquals(8, raftNode.getNextIndex().get("node2").intValue());
        assertEquals(7, raftNode.getReplicationIndex().get("node2"));

        // node3: failure
        // nextIndex decremented : 5 -> 4
        // replicationIndex = unchanged => 2
        assertEquals(4, raftNode.getNextIndex().get("node3"));
        assertEquals(2, raftNode.getReplicationIndex().get("node3"));

    }

    @Test
    public void testCommitIndexUpdateWithMajority() {
        raftNode.setState(RaftState.LEADER);
        raftNode.setCurrentTerm(1);

        raftNode.setNextIndex("node2", 4);
        raftNode.setNextIndex("node3", 4);
        raftNode.setReplicationIndex("node2", 2);
        raftNode.setReplicationIndex("node3", 1);

        when(mockLogManager.getLastLogIndex()).thenReturn(3);
        when(mockLogManager.getLogEntryTerm(1)).thenReturn(1);
        when(mockLogManager.getLogEntryTerm(2)).thenReturn(1);
        when(mockLogManager.getLogEntryTerm(3)).thenReturn(1);

        when(mockStateMachine.apply(any())).thenReturn(true);

        when(mockLogManager.getLogEntry(anyInt())).thenReturn(new RaftLogEntry(0, 1, "test"));

        // initial commit index should be -1
        assertEquals(-1, raftNode.getCommitIndex());
        System.out.println("Before updateCommitIndex:");
        System.out.println("node2 replicationIndex: " + raftNode.getReplicationIndex().get("node2"));
        System.out.println("node3 replicationIndex: " + raftNode.getReplicationIndex().get("node3"));
        System.out.println("commitIndex: " + raftNode.getCommitIndex());

        raftNode.updateCommitIndex();

        System.out.println("After updateCommitIndex:");
        System.out.println("commitIndex: " + raftNode.getCommitIndex());

        assertEquals(2, raftNode.getCommitIndex());
    }

    private void triggerElectionTimeout() {
        ArgumentCaptor<Runnable> timeoutCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(mockElectionTimer, atLeastOnce()).setTimeoutHandler(timeoutCaptor.capture());
        List<Runnable> allHandlers = timeoutCaptor.getAllValues();
        Runnable handler = allHandlers.get(allHandlers.size() - 1);
        handler.run();

    }

    private void triggerHeartbeat() {
        ArgumentCaptor<Runnable> heartbeatCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(mockHeartBeatTimer).setHeartBeatHandler(heartbeatCaptor.capture());
        heartbeatCaptor.getValue().run();
    }

    private void makeNodeLeader() {
        when(mockRpcService.sendRequestVote(any(), any(RequestVoteRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(new RequestVoteResponse(1, true)));
        triggerElectionTimeout();
        waitForState(RaftState.LEADER);

    }

    private void waitForState(RaftState expectedState) {
        int attempts = 0;
        while (raftNode.getState() != expectedState && attempts < 10) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            attempts++;
        }
    }

    private void becomeLeader() {
        when(mockRpcService.sendRequestVote(anyString(), any(RequestVoteRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(new RequestVoteResponse(1, true)));
        triggerElectionTimeout();
        waitForState(RaftState.LEADER);

    }

}
