package com.example.networking.http;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import com.example.kvstore.KVStoreOperation;
import com.example.kvstore.KVStoreResult;
import com.example.kvstore.command.DeleteCommand;
import com.example.kvstore.command.GetCommand;
import com.example.kvstore.command.PutCommand;
import com.example.kvstore.statemachine.KVStoreStateMachine;
import com.example.networking.config.NetworkConfig;
import com.example.node.RaftNode;
import com.example.raft.statemachine.StateMachine;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.net.URI;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/v1/kv")
@RequiredArgsConstructor
@Slf4j
public class KVStoreController {
    private final RaftNode raftNode;
    private final NetworkConfig networkConfig;

    @PutMapping("/{key}")
    public ResponseEntity<?> put(@PathVariable String key, @RequestBody String value) {
        log.debug("Received PUT request - key: {}, value: {}", key, value);

        if (!raftNode.isLeader()) {
            return redirectToLeader("PUT", key, value);
        }
        PutCommand command = new PutCommand(key, value);
        boolean success = raftNode.appendCommand(command);

        if (success) {
            return ResponseEntity.ok().build();
        } else {
            return ResponseEntity.status(500).body("Failed to process command");
        }

    }

    @GetMapping("/{key}")
    public ResponseEntity<?> get(@PathVariable String key) {
        log.debug("Received GET request - Key: {}", key);
        if (key == null || key.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Key cannot be empty");
        }
        GetCommand command = new GetCommand(key);
        // For mvp: allow reads frmo any node(eventual consistency)
        // if we want linearizable reads, we should redirect to leader
        try {
            // Todo: We should have a way to read from kvStore
            // To be done when we integrate kvStore with RaftNode
            KVStoreResult result = readFromKVStore(key);
            if (result.isSuccess()) {
                log.debug("GET Success: Received value {}", result.getValue());
                return ResponseEntity.ok(result.getValue());
            } else {
                log.debug("GET Failure: Received error {}", result.getError());
                if (result.getError() != null && result.getError().contains("not found")) {
                    return ResponseEntity.status(400).body("Key not found: " + key);
                }
                return ResponseEntity.status(500).body(result.getError());
            }

        } catch (Exception e) {
            return ResponseEntity.status(500).body("Error processing request");
        }
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<?> delete(@PathVariable String key) {
        if (key == null || key.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Key cannot be empty");
        }
        if (!raftNode.isLeader()) {
            return redirectToLeader("DELETE", key, "");
        }
        DeleteCommand command = new DeleteCommand(key);
        boolean success = raftNode.appendCommand(command);
        if (success) {
            return ResponseEntity.ok().build();
        } else {
            return ResponseEntity.status(404).body("Key not found or operation failed");
        }
    }

    @GetMapping("/health")
    public ResponseEntity<?> healthCheck() {
        HealthStatus status = new HealthStatus("OK", raftNode.getNodeId(),
                raftNode.isLeader() ? "LEADER" : raftNode.getState().toString());
        return ResponseEntity.ok(status);
    }

    private static class HealthStatus {
        private final String status;
        private final String nodeId;
        private final String state;

        public HealthStatus(String status, String nodeId, String state) {
            this.status = status;
            this.nodeId = nodeId;
            this.state = state;
        }

        public String getStatus() {
            return status;
        }

        public String getNodeId() {
            return nodeId;

        }

        public String getState() {
            return state;
        }
    }

    private ResponseEntity<?> redirectToLeader(String operation, String key, String value) {

        String leaderId = raftNode.getLeaderId();
        log.debug("Redirect to leader called with leaderId : {}", leaderId);
        if (leaderId != null) {
            String leaderUrl = networkConfig.getNodeUrl(leaderId) + "/api/v1/kv/" + key;
            log.debug("Redirecting {} request to leader at {}", operation, leaderUrl);
            try {
                RestTemplate restTemplate = networkConfig.createRestTemplate();
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<String> entity = new HttpEntity<>(value, headers);
                ResponseEntity<String> response = restTemplate.exchange(
                        leaderUrl,
                        HttpMethod.PUT,
                        entity,
                        String.class);
                return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
            } catch (Exception e) {
                log.error("Failed to forward request to leader", e);
                return ResponseEntity.status(503).body("Failed to forward to leader");
            }
            // return ResponseEntity
            // .status(307)
            // .location(URI.create(leaderUrl))
            // .build();
        }
        return ResponseEntity.status(503).body("No leader available, try again leader");
    }

    public KVStoreResult readFromKVStore(String key) {
        StateMachine stateMachine = raftNode.getStateMachine();
        if (stateMachine instanceof KVStoreStateMachine) {
            KVStoreStateMachine kvStateMachine = (KVStoreStateMachine) raftNode.getStateMachine();
            return kvStateMachine.getKVStore().get(key);
        } else {
            return KVStoreResult.failure("Not a kv store state machine", KVStoreOperation.GET);
        }

    }

}
