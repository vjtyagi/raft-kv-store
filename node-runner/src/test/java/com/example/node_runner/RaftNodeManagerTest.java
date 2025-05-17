package com.example.node_runner;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.kvstore.KVStore;
import com.example.log.LogManager;
import com.example.node.RaftNode;
import com.example.node_runner.config.NodeConfig;
import com.example.node_runner.service.RaftNodeManager;
import com.example.raft.statemachine.StateMachine;
import com.example.rpc.RaftRpcService;
import com.example.timer.ElectionTimer;
import com.example.timer.HeartBeatTimer;

@ExtendWith(MockitoExtension.class)
public class RaftNodeManagerTest {
    @Mock
    private NodeConfig raftConfig;
    @Mock
    private LogManager logManager;
    @Mock
    private RaftRpcService rpcService;
    @Mock
    private ElectionTimer electionTimer;
    @Mock
    private HeartBeatTimer heartBeatTimer;
    @Mock
    private StateMachine stateMachine;

    @Mock
    private KVStore kvStore;

    @Mock
    private RaftNode raftNode;

    private RaftNodeManager raftNodeManager;

    // @BeforeEach
    // public void setUp() {
    // when(raftConfig.getNodeId()).thenReturn("node1");
    // when(raftConfig.getPeers()).thenReturn(Arrays.asList("node2", "node3"));

    // raftNodeManager = new RaftNodeManager(raftConfig, raftNode);
    // }

    @Test
    public void testShouldStartRaftNodeOnInitialization() {
        // when(raftConfig.getNodeId()).thenReturn("node1");
        // when(raftConfig.getPeers()).thenReturn(Arrays.asList("node2", "node3"));
        RaftNodeManager manager = new RaftNodeManager(raftConfig, raftNode);
        verify(raftNode).start();
    }

    @Test
    public void testShouldStopRaftNodeOnShutdown() {
        RaftNodeManager manager = new RaftNodeManager(raftConfig, raftNode);
        manager.stop();
        verify(raftNode).stop();
    }

}
