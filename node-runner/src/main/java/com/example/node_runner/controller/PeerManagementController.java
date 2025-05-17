package com.example.node_runner.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

}

@Data
class UpdatePeerRequest {
    private List<String> peers;
}

@Data
class AddNodeRequest {
    private String nodeId;
}