package com.example.node_runner;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
		"raft.nodeId=test-node",
		"raft.peers=node1,node2",
		"raft.storeDir=target/test-raft-data",
		"raft.electionTimeoutMs=100",
		"raft.clientPort=8080",
		"raft.rpcPort=9090"

})
class NodeRunnerApplicationTests {

	@Autowired
	private ApplicationContext context;

	@Test
	void contextLoads() {
		assertNotNull(context, "Application context should load");
		assertTrue(context.containsBean("raftNodeManager"), "RaftNodeManager Bean should exist");
		assertTrue(context.containsBean("raftNode"), "RaftNode should exist");
	}

}
