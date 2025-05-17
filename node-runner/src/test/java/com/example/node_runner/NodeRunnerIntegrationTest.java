package com.example.node_runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.apache.catalina.connector.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import com.example.kvstore.KVStore;
import com.example.node.RaftNode;
import com.example.node_runner.config.NodeConfig;
import com.example.node_runner.service.RaftNodeManager;
import com.example.state.RaftState;

import static org.awaitility.Awaitility.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "raft.nodeId=test-node",
        "raft.peers=",
        "raft.electionTimeoutMs=300",
        "raft.electionTimeoutVarianceMs=150",
        "raft.heartbeatIntervalMs=100",
        "logging.level.com.example=DEBUG",
        "loggin.level.org.springframework=DEBUG"
})
public class NodeRunnerIntegrationTest {
    private static final Logger log = LoggerFactory.getLogger(NodeRunnerIntegrationTest.class);

    @TempDir
    Path tempDir;

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private RaftNodeManager raftNodeManager;

    @Autowired
    private NodeConfig raftConfig;

    @Autowired
    private KVStore kvStore;

    @Autowired
    private TestRestTemplate restTemplate;

    static {
        // Use sattic method to set storage directory before spring context initializes
        File tempFolder = new File("target/raft-test-" + System.currentTimeMillis());
        tempFolder.mkdirs();

        System.setProperty("raft.storageDir", tempFolder.getAbsolutePath());
        System.setProperty("raft.clientPort", "0");
        System.setProperty("raft.rpcPort", "0");

    }

    @BeforeEach
    public void setUp() throws IOException {
        System.setProperty("raft.storageDir", tempDir.toString());
        System.setProperty("raft.nodeId", "test-node");
        System.setProperty("raft.peers", "");// single node for testing
        System.setProperty("raft.clientPort", String.valueOf(port));
        System.setProperty("raft.rpcPort", String.valueOf((port * 1000)));
    }

    @AfterEach
    public void cleanup() {
        System.clearProperty("raft.storageDir");
        System.clearProperty("raft.nodeId");
        System.clearProperty("raft.peers");
        System.clearProperty("raft.clientPort");
        System.clearProperty("raft.rpcPort");

    }

    @Test
    public void testShouldStartupAndConfigureComponents() {
        assertNotNull(raftNodeManager, "RaftNodeManager should be created");
        assertNotNull(raftConfig, "RaftConfig should be craeted");
        assertNotNull(kvStore, "KVStore should be created");

        RaftNode raftNode = raftNodeManager.getRaftNode();
        assertNotNull(raftNode);

        assertEquals("test-node", raftConfig.getNodeId());

        log.info("Node state: {}", raftNode.getState());
        log.info("Node ID: {}", raftNode.getNodeId());
        log.info("Is leader: {}", raftNode.isLeader());
        log.info("Peers: {}", raftNode.getPeers());

        try {
            await().atMost(10, TimeUnit.SECONDS)
                    .pollInterval(Duration.ofMillis(500))
                    .until(() -> {
                        boolean isLeader = raftNode.isLeader();
                        System.out.println("Checking if leader: " + isLeader + ", state: " + raftNode.getState());
                        return isLeader;
                    });
        } catch (Exception e) {
            System.out.println("final node state: " + raftNode.getState());
            System.out.println("final leader status: " + raftNode.isLeader());
            throw e;
        }
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/kv/health", String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

}
