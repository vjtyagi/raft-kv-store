package com.example.cluster.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.example.cluster.Cluster;
import com.example.log.InMemoryLogManager;
import com.example.log.LogManager;
import com.example.model.AppendEntriesRequest;
import com.example.model.AppendEntriesResponse;
import com.example.model.RequestVoteRequest;
import com.example.model.RequestVoteResponse;
import com.example.node.RaftNode;
import com.example.rpc.InMemoryRaftRpcService;
import com.example.state.RaftState;
import com.example.timer.ElectionTimer;
import com.example.timer.ElectionTimerImpl;
import com.example.timer.HeartBeatTimer;
import com.example.timer.HeartBeatTimerImpl;

public class InMemoryCluster2 {
    private final Map<String, RaftNode> nodes = new HashMap<>();
    private final Map<String, InMemoryRaftRpcService> rpcServices = new HashMap<>();
    private final Map<String, ElectionTimer> electionTimers = new HashMap<>();
    private final Map<String, HeartBeatTimer> heartbeatTimers = new HashMap<>();
    private final List<String> nodeIds;

    // configuration settings
    private final int electionTmeoutBaseMs;
    private final int electionTimeoutVarianceMs;
    private final int haeartbeatIntervalMs;

    public InMemoryCluster2(List<String> nodeIds) {
        this(nodeIds, 500, 500, 100);
    }

    public void register(String nodeId, RaftNode node) {
        nodes.put(nodeId, node);
    }

    public InMemoryCluster2(List<String> nodeIds, int electionTimeoutBaseMs, int electionTimeoutVarianceMs,
            int heartbeatIntervalMs) {
        this.nodeIds = new ArrayList<>(nodeIds);
        this.electionTmeoutBaseMs = electionTimeoutBaseMs;
        this.electionTimeoutVarianceMs = electionTimeoutVarianceMs;
        this.haeartbeatIntervalMs = heartbeatIntervalMs;
    }

    // Initializes the cluster by creating all nodes and their components
    public void init() {
        for (String nodeId : nodeIds) {
            InMemoryRaftRpcService rpcService = new InMemoryRaftRpcService(nodeId);
            rpcServices.put(nodeId, rpcService);
        }
        // Register all RPC services with each other
        for (String nodeId : nodeIds) {
            InMemoryRaftRpcService rpcService = rpcServices.get(nodeId);
            for (Map.Entry<String, InMemoryRaftRpcService> entry : rpcServices.entrySet()) {
                // except the current node, register others with this rpc service
                if (!entry.getKey().equals(nodeId)) {
                    rpcService.registerNode(entry.getKey(), entry.getValue());
                }
            }
        }

        // Create nodes with all their components
        for (String nodeId : nodeIds) {
            // Create a peer list excluding this node
            List<String> peers = new ArrayList<>();
            for (String id : nodeIds) {
                if (!id.equals(nodeId)) {
                    peers.add(id);
                }
            }

            // Create components
            LogManager logManager = new InMemoryLogManager();
            InMemoryRaftRpcService rpcService = rpcServices.get(nodeId);

            ElectionTimer electionTimer = new ElectionTimerImpl(electionTmeoutBaseMs, electionTimeoutVarianceMs);
            HeartBeatTimer heartBeatTimer = new HeartBeatTimerImpl(haeartbeatIntervalMs);
            electionTimers.put(nodeId, electionTimer);
            heartbeatTimers.put(nodeId, heartBeatTimer);

            // Create and store the node
            RaftNode node = new RaftNode(nodeId, peers, logManager, rpcService, electionTimer, heartBeatTimer);
            nodes.put(nodeId, node);
        }
    }

    /**
     * Start all nodes in the cluster
     * 
     */
    public void startAll() {
        for (RaftNode node : nodes.values()) {
            node.start();
        }
    }

    /**
     * Stops all nodes in the cluster
     */
    public void stopAll() {
        for (RaftNode node : nodes.values()) {
            node.stop();
        }
    }

    /**
     * stops a specific node in the cluster
     * 
     */
    public void stopNode(String nodeId) {
        RaftNode node = nodes.get(nodeId);

        if (node == null) {
            System.out.println("Node " + nodeId + " not found or already stopped");
            return;
        }

        ElectionTimer electionTimer = electionTimers.get(nodeId);
        HeartBeatTimer heartBeatTimer = heartbeatTimers.get(nodeId);
        if (electionTimer != null) {
            electionTimer.stop();
        }
        if (heartBeatTimer != null) {
            heartBeatTimer.stop();
        }

        System.out.println("Stopping node: " + nodeId);
        // First simulate network partition by unregistering node from other nodes
        for (String peerId : nodeIds) {
            if (!peerId.equals(nodeId)) {
                InMemoryRaftRpcService peerRpc = rpcServices.get(peerId);
                if (peerRpc != null) {
                    System.out.println("Disconnecting " + peerId + " from " + nodeId);
                    peerRpc.unregisterNode(nodeId);
                }
                // Also disconnect node being stopped from other nodes
                InMemoryRaftRpcService nodeRpc = rpcServices.get(nodeId);
                if (nodeRpc != null) {
                    System.out.println("Disconnecting " + nodeId + " from " + peerId);
                    nodeRpc.unregisterNode(peerId);
                }
                // Remove stopped node from peers list of other nodes
                RaftNode peerNode = nodes.get(peerId);
                if (peerNode != null) {
                    List<String> peersList = peerNode.getPeers();
                    peersList.remove(nodeId);
                    peerNode.updatePeers(peersList);
                }
            }
        }
        //
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // remove node from nodes map and nodeIds list
        nodes.remove(nodeId);
        nodeIds.remove(nodeId);
        node.stop();
    }

    /**
     * Start a specific node in the cluster
     * 
     */
    public void startNode(String nodeId) {
        RaftNode node = nodes.get(nodeId);
        if (node != null) {
            node.start();
        }
    }

    public String getCurrentLeaderId() {
        for (Map.Entry<String, RaftNode> entry : nodes.entrySet()) {
            if (entry.getValue().getState().equals(RaftState.LEADER)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public String stopNodeAndWaitForNewLeader(String nodeId, int waitTimeoutSec) {

        stopNode(nodeId);

        try {
            return waitForLeader(waitTimeoutSec, nodeId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }

    }

    /*
     * Waits for leader to be elected in the cluster
     */
    public String waitForLeader(int timeoutSec, String... excludeNodeId) throws InterruptedException {
        long endTime = System.currentTimeMillis() + (timeoutSec * 1000L);
        String excludeId = (excludeNodeId.length > 0) ? excludeNodeId[0] : null;
        while (System.currentTimeMillis() < endTime) {
            for (Map.Entry<String, RaftNode> entry : nodes.entrySet()) {
                String nodeId = entry.getKey();
                if (excludeId != null && nodeId.equals(excludeId)) {
                    continue;
                }
                if (entry.getValue().getState() == RaftState.LEADER) {
                    return nodeId;
                }
            }
            Thread.sleep(100);
        }
        return null;
    }

    /*
     * Get a node by it's id
     */
    public RaftNode getNode(String nodeId) {
        return nodes.get(nodeId);
    }

    /**
     * Submits a command to the cluster
     * 
     * @param command The command to submit
     * @return true if the command was accepted, false otherwise
     */
    public boolean submitCommand(String command) {
        for (RaftNode node : nodes.values()) {
            if (node.getState().equals(RaftState.LEADER)) {
                return node.appendCommand(command);
            }
        }
        return false;
    }

    /**
     * Simulates a network partition by isolating a set of nodes
     * from the rest
     * 
     * @param isolatedNodes List of node Ids to isolate
     * @throws InterruptedException
     */
    public void createPartition(List<String> isolatedNodes) {
        List<String> nonIsolatedNodes = new ArrayList<>(nodeIds);
        nonIsolatedNodes.removeAll(isolatedNodes);

        // Disconnect isolated nodes from non-isolated nodes
        for (String isolated : isolatedNodes) {
            InMemoryRaftRpcService isolatedRpc = rpcServices.get(isolated);
            for (String nonIsolated : nonIsolatedNodes) {
                // Remove from isolated node's registry
                isolatedRpc.unregisterNode(nonIsolated);
                // Remove isolated node from non-isolated node's registry
                InMemoryRaftRpcService nonIsolatedRpc = rpcServices.get(nonIsolated);
                nonIsolatedRpc.unregisterNode(isolated);

                // update peer list
                RaftNode isolatedNode = nodes.get(isolated);
                if (isolatedNode != null) {
                    List<String> updatedPeers = new ArrayList<>(isolatedNode.getPeers());
                    updatedPeers.remove(nonIsolated);
                    isolatedNode.updatePeers(updatedPeers);
                }
                RaftNode nonIsolatedNode = nodes.get(nonIsolated);
                if (nonIsolatedNode != null) {
                    List<String> updatedPeers = new ArrayList<>(nonIsolatedNode.getPeers());
                    updatedPeers.remove(isolated);
                    nonIsolatedNode.updatePeers(updatedPeers);

                }
            }
        }

        // Reset election timers to accelerate new leader election
        for (String nodeId : nonIsolatedNodes) {
            RaftNode node = nodes.get(nodeId);
            if (node != null && node.getState() != RaftState.LEADER) {
                ElectionTimer electionTimer = electionTimers.get(nodeId);
                if (electionTimer != null) {
                    electionTimer.reset();
                }
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        List<String> nodeIds = List.of("node1", "node2", "node3", "node4", "node5");

        InMemoryCluster2 cluster = new InMemoryCluster2(nodeIds);
        try {
            cluster.init();
            cluster.startAll();

            String leaderId = cluster.waitForLeader(5);
            System.out.println("Leader elected: " + leaderId);

            System.out.println("==== SUBMITTING COMMANDS TO THE NEW LEADER");
            for (int i = 5; i < 10; i++) {
                boolean success = cluster.submitCommand("SET key" + i + " value " + i);
                System.out.println("Command " + i + " submitted: " + success);
                Thread.sleep(1000);
            }

            // Simulate a leader failure
            System.out.println("\n======== SIMULATING LEADER FAILURE ======");
            System.out.println("Stopping Leader " + leaderId);
            cluster.stopNode(leaderId);
            // Thread.sleep(2000);
            // Wait for new leader election
            System.out.println("======= WAITING FOR NEW LEADER ELECTION ========");
            String newLeaderId = cluster.stopNodeAndWaitForNewLeader(leaderId, 10);
            System.out.println(" New leader elected : " + newLeaderId);

            // Submit more commands to new leader
            System.out.println("==========SUBMITTING COMMANDS TO NEW LEADER =========");
            for (int i = 10; i < 15; i++) {
                boolean success = cluster.submitCommand("SET Key" + i + " value" + i);
                System.out.println("Command " + i + " submitted: " + success);
                Thread.sleep(1000);
            }
            System.out.println("Initial leader: " + leaderId + " New Leader " + newLeaderId);

        } finally {
            cluster.stopAll();
        }

    }
}
