package com.example.node_runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import com.example.node_runner.config.NodeConfig;

@SpringBootTest
@TestPropertySource(properties = {
        "raft.nodeId=test-node",
        "raft.peers=node1,node2,node3",
        "raft.storageDir=/tmp/test-raft-data",
        "raft.electionTimeoutMs=1000",
        "raft.electionTimeoutVarianceMs=500",
        "raft.heartbeatIntervalMs=200",
        "raft.clientPort=8888",
        "raft.rpcPort=9999"
})
public class RaftConfigTest {
    @Autowired
    private NodeConfig raftConfig;

    @Test
    public void shouldLoadPropertiesCorrectly() {
        assertEquals("test-node", raftConfig.getNodeId(), "nodeId should match");
        assertEquals(3, raftConfig.getPeers().size(), "should have 3 peers");
        assertTrue(raftConfig.getPeers().contains("node1"), "peers should contain node1");
        assertTrue(raftConfig.getPeers().contains("node2"), "peers should contain node2");
        assertTrue(raftConfig.getPeers().contains("node3"), "peers should contain node3");
        assertEquals("/tmp/test-raft-data", raftConfig.getStorageDir(), "storageDir should match");
        assertEquals(1000, raftConfig.getElectionTimeoutMs());
        assertEquals(500, raftConfig.getElectionTimeoutVarianceMs());
        assertEquals(200, raftConfig.getHeartbeatIntervalMs());
        assertEquals(8888, raftConfig.getClientPort());
        assertEquals(9999, raftConfig.getRpcPort());
    }
}
