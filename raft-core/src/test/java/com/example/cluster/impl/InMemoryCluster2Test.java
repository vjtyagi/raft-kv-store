package com.example.cluster.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.example.log.InMemoryLogManager;
import com.example.log.LogManager;
import com.example.node.RaftNode;
import com.example.rpc.InMemoryRaftRpcService;
import com.example.state.RaftState;
import com.example.timer.ElectionTimer;
import com.example.timer.ElectionTimerImpl;
import com.example.timer.HeartBeatTimer;
import com.example.timer.HeartBeatTimerImpl;

public class InMemoryCluster2Test {
    private InMemoryCluster2 cluster;
    private List<String> nodeIds;

    @BeforeEach
    public void setup() {
        nodeIds = Arrays.asList("node1", "node2", "node3");
        cluster = new InMemoryCluster2(nodeIds);
    }

    @AfterEach
    public void tearDown() {
        if (cluster != null) {
            cluster.stopAll();
        }
    }

    @Test
    public void testInitAndStartAll() {
        cluster.init();
        cluster.startAll();

        for (String nodeId : nodeIds) {
            RaftNode node = cluster.getNode(nodeId);
            assertNotNull(node, "Node should exists: " + nodeId);
            assertEquals(RaftState.FOLLOWER, node.getState());
        }

    }

    @Test
    public void testWaitForLeader() throws InterruptedException {
        cluster.init();
        cluster.startAll();

        String leaderId = cluster.waitForLeader(5);
        assertNotNull(leaderId, "A leader should be elected within timeout");
        RaftNode leaderNode = cluster.getNode(leaderId);
        assertEquals(RaftState.LEADER, leaderNode.getState(), "leadernode should be in leader state");

        // verify other nodes are followers
        for (String nodeId : nodeIds) {
            if (!nodeId.equals(leaderId)) {
                RaftNode followerNode = cluster.getNode(nodeId);
                assertEquals(RaftState.FOLLOWER, followerNode.getState());
            }
        }
    }

    @Test
    public void testStopAndStartNode() throws InterruptedException {
        cluster.init();
        cluster.startAll();

        String leaderId = cluster.waitForLeader(5);
        assertNotNull(leaderId, "leader should be elected within timeout");

        // Stop a non-leader node
        String nonLeaderId = nodeIds.stream().filter(id -> !id.equals(leaderId)).findFirst().orElseThrow();
        cluster.stopNode(nonLeaderId);

        String currentLeaderId = cluster.getCurrentLeaderId();
        assertEquals(leaderId, currentLeaderId);

        // Start a node
        InMemoryRaftRpcService rpcService = new InMemoryRaftRpcService(nonLeaderId);
        LogManager logManager = new InMemoryLogManager();
        ElectionTimer electionTimer = new ElectionTimerImpl(500, 500);
        HeartBeatTimer heartBeatTimer = new HeartBeatTimerImpl(100);

        List<String> peers = Arrays
                .asList(nodeIds.stream().filter(id -> !id.equals(nonLeaderId)).toArray(String[]::new));
        RaftNode newNode = new RaftNode(nonLeaderId, peers, logManager, rpcService, electionTimer, heartBeatTimer);
        newNode.start();
        assertEquals(RaftState.FOLLOWER, newNode.getState());

        newNode.stop();
    }

    @Test
    public void testLeaderFailure() throws InterruptedException {
        cluster.init();
        cluster.startAll();

        String origLeaderId = cluster.waitForLeader(5);
        assertNotNull(origLeaderId);

        String newLeaderId = cluster.stopNodeAndWaitForNewLeader(origLeaderId, 5);

        assertNotNull(newLeaderId);

        // should be diff from original leader
        assertNotEquals(origLeaderId, newLeaderId);

        RaftNode newLeaderNode = cluster.getNode(newLeaderId);
        assertEquals(RaftState.LEADER, newLeaderNode.getState());
    }

    @Test
    public void testSubmitCommand() throws InterruptedException {
        cluster.init();
        cluster.startAll();

        String leaderId = cluster.waitForLeader(5);
        assertNotNull(leaderId);

        boolean submitted = cluster.submitCommand("SET key1 value1");
        assertTrue(submitted);

        // allow sometime for replication
        Thread.sleep(500);

    }

    @Test
    public void testNetworkPartition() throws InterruptedException {
        cluster.init();
        cluster.startAll();

        String leaderId = cluster.waitForLeader(5);
        assertNotNull(leaderId);

        List<String> isolatedNodes = Arrays.asList(leaderId);
        cluster.createPartition(isolatedNodes);

        // Wait for new leader to be elected in majority partition
        String newLeaderId = null;
        long endTime = System.currentTimeMillis() + 5 * 1000;
        while (System.currentTimeMillis() < endTime) {
            newLeaderId = cluster.getCurrentLeaderId();
            if (newLeaderId != null && !newLeaderId.equals(leaderId)) {
                break;
            }
            Thread.sleep(100);
        }
        assertNotNull(newLeaderId);
        assertNotEquals(leaderId, newLeaderId);

    }

    @Test
    public void testMockedCluster() throws InterruptedException {
        RaftNode mockNode1 = mock(RaftNode.class);
        RaftNode mockNode2 = mock(RaftNode.class);
        RaftNode mockNode3 = mock(RaftNode.class);

        when(mockNode1.getNodeId()).thenReturn("node1");
        when(mockNode2.getNodeId()).thenReturn("node2");
        when(mockNode3.getNodeId()).thenReturn("node3");

        InMemoryCluster2 testCluster = new InMemoryCluster2(Arrays.asList("node1", "node2", "node3"));

        testCluster.register("node1", mockNode1);
        testCluster.register("node2", mockNode2);
        testCluster.register("node3", mockNode3);

        // Mock leader behaviour
        when(mockNode1.getState()).thenReturn(RaftState.LEADER);
        when(mockNode2.getState()).thenReturn(RaftState.FOLLOWER);
        when(mockNode3.getState()).thenReturn(RaftState.FOLLOWER);

        String leaderId = testCluster.getCurrentLeaderId();
        assertEquals("node1", leaderId);

        when(mockNode1.appendCommand(anyString())).thenReturn(true);
        boolean result = testCluster.submitCommand("TEST command");
        assertTrue(result);

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockNode1).appendCommand(commandCaptor.capture());
        assertEquals("TEST command", commandCaptor.getValue());

    }

    @Test
    public void testConcurrentOperations() throws InterruptedException {
        cluster.init();
        cluster.startAll();

        final String leaderId = cluster.waitForLeader(5);
        assertNotNull(leaderId);

        // Create multiple threads to submit commands concurrently
        final int numThreads = 5;
        final int numCommandsPerThread = 10;
        final CountDownLatch latch = new CountDownLatch(numThreads);
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            new Thread(() -> {
                try {
                    for (int j = 0; j < numCommandsPerThread; j++) {
                        String command = String.format("SET key%d_%d value%d_%d", threadId, j, threadId, j);
                        boolean success = cluster.submitCommand(command);
                        assertTrue(success);
                        Thread.sleep(10);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    fail("Exception during concurrent submission" + e.getMessage());
                } finally {
                    latch.countDown();
                }
            }).start();

        }
        boolean allCompleted = latch.await(30, TimeUnit.SECONDS);
        assertTrue(allCompleted);

    }

}
