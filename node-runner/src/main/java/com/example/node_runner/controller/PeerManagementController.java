package com.example.node_runner.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.command.ConfigChangeCommand;
import com.example.command.ConfigChangeCommand.ConfigChangeType;
import com.example.model.RaftLogEntry;
import com.example.networking.config.NetworkConfig;
import com.example.node.RaftNode;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/v1/cluster")
@RequiredArgsConstructor
@Slf4j
public class PeerManagementController {
    private final RaftNode raftNode;
    private final NetworkConfig networkConfig;

    @Data
    public static class JoinRequest {
        private String nodeId;
        private int lastLogIndex = -1;
    }

    @Data
    public static class JoinResponse {
        private boolean success;
        private String message;
        private String leaderId;
        private List<RaftLogEntry> entries;
        private int currentTerm;
        private int commitIndex;
    }

    @PostMapping("/join")
    public ResponseEntity<JoinResponse> joinCluster(@RequestBody JoinRequest request) {
        log.info("Received join request from node {}", request.getNodeId());
        JoinResponse response = new JoinResponse();
        if (!raftNode.isLeader()) {
            String leaderId = raftNode.getLeaderId();
            response.setSuccess(false);
            response.setMessage("Not the leader, Try connecting to the leader node");
            response.setLeaderId(leaderId);
            return ResponseEntity.status(307).body(response);
        }
        // Register the node's url
        // networkConfig.addNodeUrl(request.getNodeId(), request.getNodeUrl());

        // Get the log entries node needs to catch up
        List<RaftLogEntry> entries = raftNode.getLogManager().getEntriesFrom(request.getLastLogIndex() + 1);
        response.setEntries(entries);
        response.setCurrentTerm(raftNode.getCurrentTerm());
        response.setCommitIndex(raftNode.getCommitIndex());

        // Initiate join consensus process to add the node
        boolean configChangeInitiated = initiateConfigChange(request.getNodeId());
        if (configChangeInitiated) {
            response.setSuccess(true);
            response.setMessage("Join process initiated successfully. Configuration change in progress");
        } else {
            response.setSuccess(false);
            response.setMessage("Failed to initiate configuration change");

        }

        return ResponseEntity.ok(response);
    }

    private boolean initiateConfigChange(String newNodeId) {
        try {
            // Get current configuration ( all nodes including leaders)
            List<String> oldConfig = new ArrayList<>(raftNode.getPeers());
            oldConfig.add(raftNode.getNodeId());

            // Create new configuration with new node
            List<String> newConfig = new ArrayList<>(oldConfig);
            newConfig.add(newNodeId);

            // Create join consensus command
            ConfigChangeCommand jointCommand = new ConfigChangeCommand(ConfigChangeType.JOINT, oldConfig, newConfig);

            // Append command to the log
            boolean success = raftNode.appendCommand(jointCommand);

            if (success) {
                log.info("Joint consensus configuration change initiated to add node {}", newNodeId);
                // Schedule the final configuration change after sometime to allow replication
                Thread.ofVirtual().start(() -> {
                    try {
                        // Wait for joint consensus to be established
                        Thread.sleep(5000);
                        if (!raftNode.isLeader()) {
                            log.warn("{} is No longer leader, final config change couldn't be applied",
                                    raftNode.getNodeId());
                            return;
                        }
                        // Create final configuration command
                        ConfigChangeCommand finalCommand = new ConfigChangeCommand(ConfigChangeType.FINAL, null,
                                newConfig);
                        boolean finalSuccess = raftNode.appendCommand(finalCommand);
                        if (finalSuccess) {
                            log.info("Final configuration change completed for node: {}", newNodeId);
                        } else {
                            log.error("Failed to append configuration change for node {}", newNodeId);
                        }
                    } catch (Exception e) {
                        log.error("Error scheduling final configuration change", e);
                    }
                });
            }
            return success;
        } catch (Exception e) {
            log.error("Error initiating configuration change");
            return false;
        }
    }

    @PostMapping("/peers")
    public ResponseEntity<?> updatePeers(@RequestBody UpdatePeerRequest request) {
        log.info("{}: Received request to update peers to {}", raftNode.getNodeId(), request.getPeers());
        try {
            // Only leader can update cluster membership
            if (!raftNode.isLeader()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("Only leader can update cluster membership");
            }
            raftNode.updatePeers(request.getPeers());
            return ResponseEntity.ok().body(Map.of("message", "Peers updated succesfull",
                    "newPeers", request.getPeers()));
        } catch (Exception e) {
            log.error("Failed to update peers", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to update Peers: " + e.getMessage());
        }

    }

    @GetMapping("/peers")
    public ResponseEntity<?> getCurrentPeers() {
        return ResponseEntity.ok()
                .body(Map.of("nodeId", raftNode.getNodeId(),
                        "peers", raftNode.getPeers(),
                        "isLeader", raftNode.isLeader()));
    }

    @PostMapping("/add-node")
    public ResponseEntity<?> addNode(@RequestBody AddNodeRequest request) {
        log.info("{}: Request add node: {}", raftNode.getNodeId(), request.getNodeId());
        if (!raftNode.isLeader()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Only leader can add nodes to cluster");
        }
        List<String> currentPeers = new ArrayList<>(raftNode.getPeers());
        if (!currentPeers.contains(request.getNodeId())) {

            currentPeers.add(request.getNodeId());
            raftNode.updatePeers(currentPeers);
            return ResponseEntity.ok()
                    .body(Map.of("message", "Node added successfully",
                            "addNode", request.getNodeId(),
                            "newPeers", currentPeers));
        } else {
            return ResponseEntity.badRequest()
                    .body("Node already exists in cluster");
        }

    }

    @PostMapping("/bootstrap-peers")
    public ResponseEntity<?> bootstrapPeers(@RequestBody UpdatePeerRequest request) {
        log.info("{}: bootstrap request to update peers to {}", raftNode.getNodeId(), request.getPeers());
        try {
            raftNode.updatePeers(request.getPeers());
            return ResponseEntity.ok().body(Map.of(
                    "message", "Peers bootstrapped successfully",
                    "newPeers", request.getPeers()));
        } catch (Exception e) {
            log.error("Failed to bootstrap peers", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to boostrap peers: " + e.getMessage());
        }

    }

    @PostMapping("/start-join")
    public ResponseEntity<?> startJoin() {

        raftNode.setJoining(true);
        log.info("Node {} is now in joining state", raftNode.getNodeId());

        // Register leader's URL
        // networkConfig.addNodeUrl(leaderNodeId, leaderUrl);
        return ResponseEntity.ok().body(Map.of(
                "nodeId", raftNode.getNodeId(),
                "isJoining", true,
                "message", "Node is now in joining state"));

    }

}

@Data
class UpdatePeerRequest {
    private List<String> peers;
}

@Data
class AddNodeRequest {
    private String nodeId;
}